# KoawaAgent 开发与面试笔记

> 新版记录起点：2026-07-26
>
> 旧文档不作为本文件的前置依赖。研究记录统一放在 `docs/research`，实现切片和面试复盘
> 记录在本文件。

---

## 2026-07-26：R003-1 Checkpoint 副作用崩溃窗口实验

### 本步目标

不修改生产代码，通过最小实验确认当前 Step Checkpoint 是否能防止工具副作用重复执行。

### 对应代码

```text
src/test/java/com/koawa/agent/agent/research/
  CheckpointSideEffectCrashExperimentTest.java
```

完整研究记录：

```text
docs/research/R003-checkpoint-tool-side-effects.md
```

### 核心流程

```text
revision 0
  → 工具执行成功，计数器变为 1
  → Step Checkpoint 失败
  → 数据库状态仍为 revision 0 / nextStep 0
  → 从旧 Snapshot 恢复
  → 同一工具再次执行，计数器变为 2
```

### 实验结果

```text
Tests run: 1
Failures: 0
Errors: 0
Skipped: 0
```

本测试通过表示“失败模式被稳定复现”，不表示系统已经解决问题。

### 工程结论

- Step Checkpoint 只能避免已提交 Step 被重放。
- Checkpoint fail-fast 不能撤销已经产生的外部副作用。
- 当前 Snapshot 无法区分“工具没执行”和“工具可能执行成功但结果没保存”。
- 在未知结果场景下直接 Resume 并自动重放是不安全的。

### 面试问题

#### 问题一：有了 Checkpoint，为什么还需要工具执行账本？

参考回答：

Checkpoint 保存的是 Runtime 在某个提交边界的状态。外部工具成功与 Checkpoint 成功不是
一个原子事务，两者之间崩溃会让 Runtime 看不到已经发生的副作用。工具账本用于在执行前
记录稳定调用身份和状态，让恢复过程至少能识别重复或未知结果。

#### 问题二：Checkpoint 保存失败后立即停止，为什么仍不够？

参考回答：

立即停止只能防止 Runtime 继续执行后续动作，不能回滚已经完成的外部调用。例如远端写入
已经成功，随后数据库不可用，恢复时仍然只能看到旧 Snapshot。

#### 问题三：能否依赖数据库事务实现 Exactly Once？

参考回答：

如果工具副作用发生在数据库事务之外，例如远端 MCP、文件系统、Git 或 Shell，就无法和
Checkpoint 数据库形成单一原子事务。工程上通常采用幂等键、执行账本、结果查询、补偿和
未知结果人工处理，而不是轻易承诺 Exactly Once。

#### 问题四：这个实验为什么使用计数器？

参考回答：

计数器是最小可观测副作用。它排除了网络、文件系统和数据库方言等无关变量，只验证
AgentLoop 的执行顺序与 Snapshot 恢复语义。后续还需要针对真实 Patch、Shell、Git 和
MCP 场景补充更高等级证据。

### 当前未完成

- 尚未读取成熟框架的一手实现证据。
- 尚未测试不同副作用类别。
- 尚未形成 ADR。
- 尚未修改生产 Runtime。

---

## 2026-07-26：R003-2 成熟 Durable Runtime 一手证据对照

### 本步目标

只读取官方文档和官方源码，确认 LangGraph、Temporal、Restate 是否真的解决了任意外部
副作用 Exactly Once，避免根据产品宣传或二手文章臆测。

### LangGraph

已确认：

- Resume 从 Checkpoint 边界重放，不是恢复 Python 调用栈。
- 已完成 Task 结果会复用。
- 已开始但未完成的 Task 仍可能重跑。
- interrupt 所在 Node 会从头执行，中断前副作用必须幂等。
- 官方 PostgreSQL Checkpointer 会按 taskId 保存中间 writes。

对应到 KoawaAgent：

```text
AgentStep ≈ 已完成 Task Result
当前缺少 ≈ Pending/Running Tool Task
```

### Temporal

Temporal 官方给出的典型故障与我们的实验相同：

```text
Activity 成功
  → Worker 上报前崩溃
  → Event History 没记录完成
  → Activity 被重试
```

它保证完成结果在 Workflow 中只被观察一次，但 Activity 函数仍可能执行多次。因此官方
要求写 Activity 幂等，并推荐用稳定 Activity 身份形成外部幂等键。

### Restate

Restate 使用 log-first 和 Journal：

```text
Step Result
  → 复制日志 quorum commit
  → 才确认 Step 已发生
  → 后续重试复用结果
```

它还使用 epoch fencing 防止旧执行者继续写入。但 Restate 仍明确承认：外部 Side Effect
在结果进入 Durable Log 前可能执行多次。

### 源码取证

本轮将三个官方仓库浅克隆到系统临时目录，固定版本：

```text
LangGraph    30c4d58db86455128e42ddec96b1ba53c553ba22
Temporal     f68c9bc714c93b3ff8c4c7135e58089811ecfaec
Restate      d533e91d4871e8621fc29c592bafbbcb400d2eaa
```

源码确认：

- LangGraph 的 `checkpoint_writes` 使用 taskId 作为主键组成部分，Pregel Runner 在 Task
  成功、失败、中断或取消结束时提交 writes。
- Temporal Java SDK 同时保留 logical activityId 与 physical attempt。
- Restate 在 `stateMachine.run(name)` 判断结果已 Replay 时不执行 Closure；否则执行
  Closure，并在返回后提出 Run Completion。

因此三个实现都显式区分：

```text
逻辑执行身份
实际执行 Attempt
已持久化结果
```

KoawaAgent 当前只有 taskId、stepIndex 和完成后的 Snapshot，还没有独立 ToolCall 身份与
Attempt。

### 对 KoawaAgent 的工程启发

至少需要把四个问题分开：

1. Snapshot/Checkpoint：哪些完整结果已经提交？
2. Tool identity：当前逻辑调用到底是哪一个？
3. Deduplication：同一调用再次到达时谁负责去重？
4. Claim/fencing：哪个执行者有权继续推进任务？

不能用一个 revision 同时回答这四个问题。

### 面试问题

#### 问题一：Temporal 为什么宣称可靠执行，却仍要求 Activity 幂等？

参考回答：

Temporal 可以可靠保存 Workflow History，并保证已记录完成的 Activity 不因 Replay 重跑。
但 Activity 的外部副作用与服务端完成记录之间仍存在网络和进程崩溃窗口。Activity 可能
执行多次，而完成结果只被 Workflow 观察一次，所以写操作必须幂等。

#### 问题二：LangGraph 的 Task 与普通 Node 有什么恢复差异？

参考回答：

Resume 会从 Checkpoint 边界重放控制流。放进 Task 的非确定性或副作用结果可以单独持久化
并在恢复时复用；直接写在可重跑 Node 或 entrypoint 中的代码可能再次执行。即使是 Task，
如果开始后未完成，也仍应设计成幂等。

#### 问题三：有了 stable toolCallId 是否就不会重复写？

参考回答：

不会。stable toolCallId 只提供“这是同一个逻辑调用”的身份。真正阻止重复副作用，需要
被调用方按这个 ID 去重、Runtime 复用已保存结果，或先查询外部状态。没有执行去重机制，
稳定 ID 只是可观测字段。

#### 问题四：Claim/Lease 能解决副作用重复吗？

参考回答：

Claim 解决两个执行者同时推进同一任务；它不能消除一个执行者在工具成功后、结果持久化前
崩溃的窗口。这两个问题分别属于并发所有权和外部副作用原子性。

#### 问题五：为什么不直接采用 Temporal 或 Restate？

参考回答：

它们证明了问题边界和候选机制，但 KoawaAgent 当前目标是轻量 Java Coding Harness，而且
不同工具具有不同副作用语义。应先用实验确定 ToolCall 身份、Ledger 和未知结果策略，再
评估是否需要重量级 Durable Runtime，不能因为框架成熟就跳过适配成本和语义分析。

### 当前未完成

- 尚未对只读、幂等写和非幂等写进行对照实验。
- 尚未测量不同方案的额外写入次数和恢复行为。
- 尚未形成 ADR。
- 尚未修改生产代码。

---

## 2026-07-26：全面评审与治理整改轮

### 本轮目标

对项目与三份 `.agents` 治理文档做一次全面评审（REVIEW 模式），随后按用户批准执行
六个整改包。

### 评审主要发现（详见 .agents/CHANGELOG.md 对应修订）

- P1：Operating Guide 7.4 要求 PostgreSQL/Testcontainers 验证，但 pom 无依赖，
  JDBC Store 仅经 H2 验证；Execution Plan §2.1 的基线声明超出验证事实。
- P1：治理文档缺少代理发现入口（无 AGENTS.md/CLAUDE.md）。
- P2：Execution Plan/README 引用已删除的 docs/KoawaAgent-Runtime-Plan.md；
  状态机含代码不存在的 CREATED；M3-S3 审批依赖倒置；会话历史持久化无切片归属；
  评测基础设施（M11）过晚；无 CI；工作区未提交改动横跨多个语义。
- P3：双 JSON 库（gson+Jackson）；61 个源文件带错误的 ASF 版权头；
  无 docs/adr/ 落地；研究流程无时间盒。

### 本轮实施内容

1. 新增 `AGENTS.md`/`CLAUDE.md` 代理入口；修复 Execution Plan §3.1/§23.1 与
   README 的失效引用。
2. 三份治理文档升级 v1.1（详见 `.agents/CHANGELOG.md`）：删除 CREATED、
   基线声明如实化、M0-S5 增加会话历史持久化、M1 出口增加最小评测场景、
   M1-S5/S6 与 M8-S1 预拆、M3-S3 推迟至 M5-S3 后、新增文档修订流程（Guide §14）、
   "涉及架构决策"判定标准（Guide §2.3）、研究泄压阀与时间盒（Protocol §3/§9）。
3. 新增 `docs/adr/ADR-TEMPLATE.md` 与 `docs/adr/ADR-001-checkpoint-latest-only-snapshot.md`
   （追认单行最新快照取舍，审计目标由 M1-S2/M5-S4 承担）。
4. 新增 `.github/workflows/ci.yml`（mvn verify）与
   `PostgresJdbcAgentCheckpointStoreTest`（Testcontainers，本地无 Docker 自动跳过）；
   pom 增加 testcontainers 依赖。
5. `AgentCheckpointLifecycleException` 迁入 `agent/exception`，异常包迁移收尾。
6. JSON 统一到 Jackson：`AgentTaskSnapshotMapper`、`AgentActionParser`、
   `AgentRequestAssembler`、`OpenAiCompatibleLlmService` 移除 gson，pom 删除 gson 依赖。
7. 新增 `docs/research/README.md` 索引；预留 ADR-002（R003 产出）路径。

---

## 2026-07-28：R003-3 工具副作用类别故障对照

### 本步目标

保持崩溃点、Checkpoint、恢复流程和 Runner 不变，只替换工具行为，对比只读、幂等写和
非幂等写在 Resume 重放后的真实差异。

本步仍是 `RESEARCH`，没有修改生产代码、数据库或依赖。

### 对应代码

```text
src/test/java/com/koawa/agent/agent/research/
  ToolSideEffectClassCrashExperimentTest.java
```

公共实验夹具的核心逻辑：

```java
assertThrows(
        AgentCheckpointLifecycleException.class,
        () -> runner(operation, failingCheckpoint).run(initialState));

AgentCheckpointService.LoadedAgentCheckpoint loaded =
        checkpointService.load(taskId).orElseThrow();

AgentState resumedState = loaded.state();
runner(operation, AgentCheckpointLifecycle.NOOP).run(resumedState);
```

这里不是手工把 `currentStep` 改回 0，而是从 Store 中仍然存在的 revision 0 重建状态，
确保模拟的就是 Runtime 重启后只能看到旧 Snapshot 的情况。

三种操作只替换 `operation`：

```java
// 只读：执行两次，外部状态不变
() -> {
    executionAttempts.incrementAndGet();
    return externalValue.get();
}

// 幂等写：执行两次，同一个 key 只落一条逻辑记录
() -> {
    executionAttempts.incrementAndGet();
    externalRecords.putIfAbsent(IDEMPOTENCY_KEY, "created");
    return externalRecords.get(IDEMPOTENCY_KEY);
}

// 非幂等写：执行两次，追加两条记录
() -> {
    int attempt = executionAttempts.incrementAndGet();
    externalRecords.add("created-by-attempt-" + attempt);
    return externalRecords.get(externalRecords.size() - 1);
}
```

### 实验结果

执行：

```text
mvn -q -Dtest=ToolSideEffectClassCrashExperimentTest test
```

结果：

```text
Tests run: 3
Failures: 0
Errors: 0
Skipped: 0
```

| 工具类别 | 实际执行 | 外部状态变化 | 恢复含义 |
|---|---:|---:|---|
| 只读 | 2 次 | 0 次 | 无写入重复，但会重复成本，结果也可能随外部数据变化 |
| 幂等写 | 2 次 | 1 次 | 只有稳定 key 且执行方去重时，逻辑状态才不重复 |
| 非幂等写 | 2 次 | 2 次 | 自动重放直接产生重复副作用 |

这是一组受控故障对照，因此对“副作用类别会改变恢复结果”的结论达到 E4；它不代表真实
Patch、Git、Shell 或 MCP 已达到 E4。

### 工程性设计讲解

#### 1. 为什么要同时记录执行次数和状态变化次数？

幂等的定义不是“函数只执行一次”，而是“相同操作重复执行后，外部逻辑状态与执行一次
相同”。因此需要分开测量：

```text
physical attempts = 2
logical effects   = 1
```

如果只断言最终只有一条记录，就会漏掉重复费用、重复限流、额外延迟和审计噪声。

#### 2. 稳定幂等键应该从哪里来？

实验用常量模拟同一逻辑 ToolCall 的稳定身份。生产设计不能为每次 Attempt 生成新 key，
否则外部系统会把重放视为新请求。更合理的关系是：

```text
logical toolCallId
  → stable idempotency key
  → attempt 1 / attempt 2 / ...
```

`attempt` 用于审计物理执行次数，`toolCallId` 用于识别逻辑调用；两个字段不能互相替代。

#### 3. 为什么只读工具也不能简单写成“永远安全重试”？

只读不会写坏外部状态，但可能存在：

- 第二次读取时数据已经变化，恢复后的模型看见不同结果。
- 查询需要付费或受限流。
- 读取本身会触发访问审计、缓存填充等隐藏副作用。
- 大型 build/status 查询会重复消耗 CPU 和时间。

因此 `READ_ONLY` 更接近“通常允许自动重试”，而不是无条件的 Exactly Once。

#### 4. 为什么非幂等写需要 `OUTCOME_UNKNOWN`？

当 Runtime 只看到 `RUNNING` 或旧 Snapshot 时，无法区分：

```text
外部写入没有发生
外部写入已经成功，但成功结果没落账
```

直接重放会把第二种情况写两次；直接标记失败又可能隐藏已经发生的写入。对高风险操作，
更安全的路径是先用稳定身份查询外部结果，无法确认时进入 `OUTCOME_UNKNOWN`，等待人工
确认、补偿或明确批准重试。

### 面试问题

#### 问题一：幂等是否等于 Exactly Once？

参考回答：

不等于。幂等允许操作执行多次，但要求最终逻辑状态与执行一次相同。实验中幂等写的物理
执行次数是 2，外部记录数是 1；重复调用的成本、延迟和限流仍然存在。

#### 问题二：有稳定 toolCallId，为什么还要外部系统支持幂等？

参考回答：

toolCallId 只回答“这是不是同一个逻辑调用”。如果执行方不按这个 ID 查询或去重，两次
请求仍会产生两次写入。稳定身份是去重的前提，不是去重本身。

#### 问题三：为什么恢复策略必须区分副作用类别？

参考回答：

同一崩溃与重放流程下，只读、幂等写和非幂等写都执行了两次，但外部状态分别变化 0、1、
2 次。统一重放策略会对非幂等写造成破坏，也会掩盖只读与幂等写各自的成本和前置条件。

#### 问题四：Tool Ledger 能消除所有未知结果吗？

参考回答：

不能。Ledger 可以在调用前记录 `PREPARED/RUNNING`，保存稳定调用身份，并在成功落账后
复用结果；但外部操作成功与 `SUCCEEDED` 落账之间仍不是原子事务。这个窗口仍需要幂等键、
外部结果查询、补偿或 `OUTCOME_UNKNOWN`。

#### 问题五：Git commit 应该属于哪一类？

参考回答：

不能只按工具名称静态认为它幂等。相同 tree/message 是否已经产生 commit 可以查询，但
重复执行可能生成不同 commit ID，Hook 也可能有副作用。生产策略应把它视为可查询但有
副作用的写操作：恢复时先按预期 tree、parent 和 message 查询，无法确认时不盲目重放。

### 当前未完成

- 尚未把具体工具映射到恢复矩阵。
- 尚未比较 Step-only、幂等键、Ledger、结果查询和人工处理的组合成本。
- 尚未形成 ADR；没有进入 Tool Ledger 生产实现。
- 尚未针对真实文件系统、Git、Shell 或远端 MCP 做故障注入。

---

## 2026-07-29：R003-4 工具恢复方案对照

### 本步目标

在同一个故障点比较五种恢复方案，回答两个工程问题：

1. Tool Ledger 本身是否能阻止副作用重复？
2. 安全恢复需要 Ledger、幂等键、结果查询和人工处理如何组合？

本步仍是 `RESEARCH`，新增代码只位于测试目录。

### 对应代码

```text
src/test/java/com/koawa/agent/agent/research/
  ToolRecoveryStrategyExperimentTest.java
```

实验固定在：

```text
外部写入成功
  → Step 未保存
  → Ledger 未写 SUCCEEDED
  → 崩溃
```

只替换恢复策略：

```java
switch (strategy) {
    case STEP_ONLY_RETRY -> {
        executionAttempts++;
        externalSystem.append(OPERATION_ID);
    }
    case IDEMPOTENCY_KEY_RETRY -> {
        executionAttempts++;
        externalSystem.applyOnce(OPERATION_ID);
    }
    case LEDGER_BLIND_RETRY -> {
        executionAttempts++;
        externalSystem.append(OPERATION_ID);
        ledger.transitionTo(LedgerState.SUCCEEDED);
    }
    case LEDGER_QUERY_THEN_REUSE -> {
        boolean resultExists = externalSystem.contains(OPERATION_ID);
        ledger.transitionTo(LedgerState.SUCCEEDED);
    }
    case LEDGER_STOP_FOR_MANUAL ->
            ledger.transitionTo(LedgerState.OUTCOME_UNKNOWN);
}
```

### 实验结果

```text
mvn -q -Dtest=ToolRecoveryStrategyExperimentTest test

Tests run: 5
Failures: 0
Errors: 0
Skipped: 0
```

相关研究回归：

```text
R003 tests run: 9
Failures: 0
Errors: 0
Skipped: 0
```

全量回归：

```text
mvn test
Tests run: 108
Failures: 0
Errors: 0
Skipped: 4
BUILD SUCCESS
```

跳过的 4 个测试全部来自 `PostgresJdbcAgentCheckpointStoreTest`，原因是本机没有可用
Docker 环境，Testcontainers 按项目约定自动跳过。因此本轮没有获得新的 PostgreSQL
证据；R003-4 也没有宣称验证 PostgreSQL 语义。

| 方案 | 执行次数 | 外部效果 | Ledger 写 | 查询 | 自动完成 |
|---|---:|---:|---:|---:|---|
| Step-only 重试 | 2 | 2 | 0 | 0 | 是，结果错误 |
| 同幂等键重试 | 2 | 1 | 0 | 0 | 是 |
| Ledger 后盲目重试 | 2 | 2 | 3 | 0 | 是，结果错误 |
| Ledger 后查询 | 1 | 1 | 3 | 1 | 是 |
| Ledger 后人工处理 | 1 | 1 | 3 | 0 | 否 |

这里的 Ledger 写次数只代表实验中的 `PREPARED → RUNNING → 终态`，不包含 Step
Checkpoint，不能直接换算成数据库性能。

### 工程性设计讲解

#### 1. Ledger 到底解决什么？

Ledger 解决的是“恢复时缺少证据”：

```text
没有 Ledger：
只知道 Step 没保存

有 Ledger：
知道哪个 toolCallId、哪些参数、执行到了 PREPARED/RUNNING/SUCCEEDED 哪个状态
```

它没有能力回滚外部系统，也不能与任意文件、Git、Shell 或 MCP 组成原子事务。因此恢复
看到 `RUNNING` 后如何处理，才真正决定是否重复副作用。

#### 2. 为什么要有 PREPARED 和 RUNNING？

`PREPARED` 表示逻辑调用、参数哈希和权限决策已经固定，但工具尚不允许执行；`RUNNING`
表示执行前记录已经持久化完成，工具现在可以被调用。

关键顺序必须是：

```text
write PREPARED
  → write and commit RUNNING
  → execute external tool
```

如果 Runtime 在 `RUNNING` 提交完成前就调用工具，那么恢复看到 `PREPARED` 时也无法判断
工具是否执行过，两个状态就失去区分价值。

#### 3. 为什么 Ledger 后盲目重试仍会重复？

Ledger 只显示第一次 Attempt 停在 `RUNNING`。它没有证明外部写入失败。若恢复策略直接
再调用一次非幂等写，外部效果仍从 1 变成 2。实验中它甚至比 Step-only 多付出三次
Ledger 写入，却没有提高副作用安全性。

#### 4. 结果查询为什么优先于重试？

查询可以把：

```text
RUNNING + 不确定
```

转换成：

```text
外部结果存在 → 补记 SUCCEEDED
外部结果明确不存在 → 根据副作用类别决定是否重试
查询仍不确定 → OUTCOME_UNKNOWN
```

但查询条件必须唯一关联逻辑 ToolCall。仅仅搜索“有没有类似 Git commit”不够可靠。

#### 5. sideEffectClass 为什么不能承担全部恢复信息？

副作用类别和恢复能力是两个维度：

- Patch 是写操作，但可以比较文件哈希。
- Git commit 是高风险写，但可以查询 parent/tree/message。
- MCP 写操作可能支持幂等键，也可能什么都不支持。

因此 ADR 应分别讨论：

```text
sideEffectClass
supportsIdempotencyKey
supportsResultLookup
unknownOutcomeRisk
```

字段名称和枚举值尚未批准，本轮只确认需要表达这些独立语义。

#### 6. 安全性和可用性如何取舍？

- 盲目重试：任务容易继续，但可能破坏外部状态。
- `OUTCOME_UNKNOWN`：不会再次写入，但任务需要人工处理。
- 查询后复用：理想情况下兼顾两者，但依赖可靠的查询能力。

所以恢复策略不是单纯追求“自动完成率最高”，而是根据副作用风险决定允许牺牲多少
可用性。

### 初步恢复矩阵

| 场景 | 建议恢复动作 |
|---|---|
| Ledger 已 `SUCCEEDED` | 直接复用保存结果 |
| `PREPARED` 且保证工具未启动 | 重新取得执行权后执行 |
| `RUNNING` + 只读 | 在预算内重试 |
| `RUNNING` + 幂等键 | 使用原 key 重试 |
| `RUNNING` + 可查询 | 先查询再决定 |
| `RUNNING` + 非幂等、不可查询 | `OUTCOME_UNKNOWN` |

### 面试问题

#### 问题一：为什么有 Tool Ledger 仍然不能承诺 Exactly Once？

参考回答：

Ledger 数据库与外部文件、Git 或 MCP 不是同一个原子事务。外部操作成功到 `SUCCEEDED`
落账之间仍有崩溃窗口。Ledger 能记录 `RUNNING` 和稳定调用身份，但仍需要外部幂等、结果
查询或未知结果人工处理。

#### 问题二：为什么不能看到 RUNNING 就自动重试？

参考回答：

`RUNNING` 表示工具可能尚未完成，也可能已经成功但结果没落账。对非幂等写自动重试会产生
第二次副作用。实验中 Ledger 盲目重试仍然执行两次、写入两次。

#### 问题三：PREPARED 和 RUNNING 有什么区别？

参考回答：

`PREPARED` 固定调用身份、参数和权限，但工具尚未获得执行许可；`RUNNING` 必须在外部
调用前持久化成功。这样恢复看到 `PREPARED` 才能确认工具没有启动，而看到 `RUNNING` 才
进入可能成功、结果未知的处理分支。

#### 问题四：OUTCOME_UNKNOWN 是失败状态吗？

参考回答：

它不是普通业务失败，而是 Runtime 无法判断外部副作用是否发生。将它混入 `FAILED` 会诱导
自动重试，也会丢失人工核查的语义。它应该暂停自动推进，并保留查询、补偿或批准重试入口。

#### 问题五：查询外部结果后就一定安全吗？

参考回答：

不一定。查询必须能唯一关联原 ToolCall，并区分成功、明确不存在和仍不确定三种结果。
查询超时、条件不唯一或外部数据被并发修改时，仍应进入 `OUTCOME_UNKNOWN`，不能把
“没查到”直接等价为“没执行”。

#### 问题六：Ledger 带来的成本是什么？

参考回答：

每次工具调用至少增加执行前和执行后的持久化，可能还有审批、Attempt 和未知结果状态；
这会增加写放大、延迟、索引和清理成本。实验只统计了相对写次数，真实 PostgreSQL 成本
必须在实现后用 Testcontainers 和指标验证。

### 当前结论与下一步

R003 已达到三次最小实验的时间盒：

1. 复现当前重复副作用。
2. 对照三种副作用类别。
3. 对照五种恢复方案。

下一步必须转入 `R003-5 DESIGN`，起草 Tool Side-effect Recovery ADR。ADR 未经人工批准
前，不实现生产 Tool Ledger。

---

## 2026-07-29：R003-5 Tool Side-effect Recovery ADR 草案

### 本步目标

将 R003-1 至 R003-4 的证据收敛为可人工审批的架构决策：

```text
docs/adr/ADR-002-tool-side-effect-recovery.md
```

ADR 状态为 `Proposed`，本步没有修改生产代码、数据库、依赖或测试。

### 当前代码观察

当前 `PreparedToolCall` 只有：

```java
public record PreparedToolCall(
        String toolId,
        Map<String, Object> parameters,
        McpToolExecutor executor
) {
}
```

它是 Policy 与执行之间的进程内对象，不包含稳定 `toolCallId`、参数哈希、Attempt、状态
或结果，也不能持久化 `executor`。因此未来 Ledger 必须是独立领域对象，不能直接序列化
`PreparedToolCall`。

### ADR 核心决策

#### 1. Checkpoint 与 Ledger 分工

```text
Checkpoint：任务执行到哪一步
Ledger：某个具体 ToolCall 执行到了什么状态
```

两者不会合并成一个巨大 Snapshot。

#### 2. Runtime ToolCall ID 与 Provider Call ID 分离

Runtime 创建并持久化 `toolCallId`，作为恢复与幂等主身份；Provider 原生 ID 单独保存。

原因是：

- 当前文本 JSON v1 没有可靠 Provider call ID。
- 不同 Provider 的 ID 作用域和稳定性可能不同。
- 即使 Provider 没有 ID，Runtime 也必须在执行前建立持久化身份。

同一个逻辑 ToolCall 的 Attempt：

```text
toolCallId = 不变
attempt    = 1, 2, 3...
```

#### 3. 状态机

```text
PREPARED
  → WAITING_FOR_APPROVAL（可选）
  → RUNNING
  → SUCCEEDED / FAILED / OUTCOME_UNKNOWN
```

核心顺序：

```text
RUNNING 持久化成功
  → 执行外部工具
  → SUCCEEDED 持久化成功
  → 写入 AgentStep Checkpoint
```

这样 Step 保存失败时，只要 Ledger 已经 `SUCCEEDED`，Resume 就能复用结果，不重新调用
工具。

#### 4. FAILED 与 OUTCOME_UNKNOWN 必须分开

```text
FAILED
  = 已经确认失败，可以根据 typed failure 决定是否重试

OUTCOME_UNKNOWN
  = 不知道外部副作用是否已经发生，禁止自动盲目重试
```

网络超时不一定等于失败。对于远端写操作，请求可能已经成功，只是响应没有返回。

#### 5. 恢复决策优先级

```text
复用 SUCCEEDED 结果
  → 权威查询
  → 使用原幂等键重试
  → 只读预算重试
  → OUTCOME_UNKNOWN / 人工处理
```

#### 6. M0 不提前实现 Ledger

Ledger 位于 M5-S4。近期 M0 只完成 Resume 闭环，但必须保留保守边界：

```text
没有 Ledger 证据
  → 不能宣称缺失 Step 的副作用工具可以安全自动重放
```

### 面试问题

#### 问题一：为什么 toolCallId 由 Runtime 维护，而不是完全依赖 Provider？

参考回答：

Provider ID 需要保留，但 Runtime 的恢复语义不能依赖所有 Provider 都提供相同稳定性和
作用域的 ID。Runtime 在 `PREPARED` 前生成自己的持久化身份，同时把 Provider ID 作为
关联字段保存，既保留原生协议信息，又能支持当前没有原生 ToolCall 的 v1 路径。

#### 问题二：为什么 SUCCEEDED 必须先于 Step Checkpoint？

参考回答：

如果 Step 先保存而 Ledger 仍是 `RUNNING`，两个持久化来源会冲突。先保存 `SUCCEEDED`
可以保证 Step 保存失败时仍有工具成功证据，Resume 只需要重新投影 Step，不需要重新执行
外部工具。

#### 问题三：FAILED 和 OUTCOME_UNKNOWN 为什么不能合并？

参考回答：

`FAILED` 表示结果确定，Policy 可以判断是否重试；`OUTCOME_UNKNOWN` 表示副作用可能已经
成功。合并后，普通失败重试机制可能再次执行付款、发信、Patch 或 commit，造成重复写。

#### 问题四：为什么 sideEffectClass 和查询能力要分开？

参考回答：

副作用描述操作性质，查询能力描述恢复手段。例如 Git commit 是写操作，但可以按
parent/tree/message 查询；MCP 写入可能支持幂等键，也可能不可查询。只用一个枚举会把
两个独立维度混在一起。

#### 问题五：为什么现在不直接实现 Ledger？

参考回答：

当前严格路线要求先完成 M0 Resume、M1 协议和后续工具基础；Ledger 属于 M5-S4。本 ADR
先固定安全不变量，让 M0 不做危险承诺，也避免未来实现时根据数据库便利性反推语义。

### 尚待人工决定

- 是否接受 Runtime 自有 `toolCallId`。
- 是否接受 `PREPARED/RUNNING` 两阶段执行前记录。
- 是否接受 `OUTCOME_UNKNOWN` 禁止自动重试。
- 是否同意 M0 保守 Resume、M5-S4 再实现 Ledger 的阶段安排。

只有 ADR 状态从 `Proposed` 改为 `Accepted` 后，才能把这些决策作为生产实现依据。

---

## 2026-07-29：M0-S1 Resume 用例与状态迁移矩阵

### 本步目标

本步进入 `IMPLEMENT` 模式，只完成执行计划中的 `M0-S1`：

- 定义 `AgentResumeCommand`。
- 定义 `AgentResumeResult`。
- 根据持久化 Snapshot 评估 Resume 状态。
- 明确 terminal 状态拒绝规则。

本步不消费 Interrupt、不修改 Checkpoint、不取得执行权、不启动 Agent Loop，也不提前实现
M5-S4 Tool Ledger。

### 新增源码

```text
src/main/java/com/koawa/agent/agent/checkpoint/AgentResumeCommand.java
src/main/java/com/koawa/agent/agent/checkpoint/AgentResumeResult.java
src/main/java/com/koawa/agent/agent/checkpoint/AgentResumeService.java
src/test/java/com/koawa/agent/agent/checkpoint/AgentResumeServiceTest.java
```

### `toolId` 与 `toolCallId`

两个字段标识的不是同一层对象：

```text
toolId
  = 调用哪一种工具
  = 工具定义/能力的身份
  = 例如 git_commit

toolCallId
  = 这一次逻辑调用是谁
  = 一次调用实例的身份
  = 例如 call-7f31
```

同一个工具可以被调用多次：

| toolId | toolCallId | 参数 | 含义 |
| --- | --- | --- | --- |
| `git_commit` | `call-101` | `message=A` | 第一次逻辑调用 |
| `git_commit` | `call-102` | `message=B` | 第二次逻辑调用 |

如果 `call-101` 因超时需要恢复，仍使用同一个 `toolCallId`，并递增 `attempt`；如果参数发生
实质变化，则是新的逻辑调用，必须生成新的 `toolCallId`。

当前 `PreparedToolCall` 只有 `toolId`，因为它只负责把某类工具交给执行器。稳定
`toolCallId` 属于 M5-S4 Tool Ledger，本切片没有提前把它塞进 Resume 协议。

### Resume Command

```java
public record AgentResumeCommand(
        String taskId,
        long expectedRevision,
        String interruptId
) {
}
```

- `taskId`：定位要恢复的任务。
- `expectedRevision`：调用方最后看到的版本。实际版本不同就抛
  `CheckpointConflictException`，避免基于旧快照作决定。
- `interruptId`：可选。恢复 `WAITING_FOR_INPUT` 时必须与当前 Pending Interrupt 匹配；
  普通 `RUNNING` 恢复不允许携带旧 Interrupt ID。

Command 在构造时完成边界规范化：`taskId` 去除首尾空白；空白 `interruptId` 归一化为
`null`；负 revision 直接拒绝。

### Resume Result

```java
public record AgentResumeResult(
        String taskId,
        long revision,
        AgentTaskStatus currentStatus,
        NextAction nextAction,
        RejectionReason rejectionReason
) {
}
```

Result 不只返回 `boolean`，而是显式告诉上层下一切片应该做什么：

```text
ACQUIRE_EXECUTION_CLAIM
CONSUME_USER_INPUT_INTERRUPT
REJECT
```

拒绝原因也是 typed value：

```text
INTERRUPT_ID_REQUIRED
INTERRUPT_ID_MISMATCH
INTERRUPT_ID_NOT_APPLICABLE
APPROVAL_RESUME_NOT_SUPPORTED
TERMINAL_STATUS
```

构造器保证不变量：只有 `REJECT` 才能携带拒绝原因；可继续的结果必须使用 `NONE`。这样
调用方不会遇到“accepted=true 但同时返回错误原因”的矛盾结果。

### 状态迁移矩阵

| 当前状态 | 本步评估结果 | 原因 |
| --- | --- | --- |
| `RUNNING`，无 interruptId | `ACQUIRE_EXECUTION_CLAIM` | M0-S4 取得执行权后才能运行 |
| `RUNNING`，有 interruptId | `REJECT` | 旧 Interrupt 不能用于普通运行态 |
| `WAITING_FOR_INPUT`，ID 匹配 | `CONSUME_USER_INPUT_INTERRUPT` | 实际一次性消费留给 M0-S3 |
| `WAITING_FOR_INPUT`，ID 缺失/不匹配 | `REJECT` | 防止回复投递给错误中断 |
| `WAITING_FOR_APPROVAL` | `REJECT` | 审批恢复尚未进入当前里程碑 |
| `COMPLETED/FAILED/CANCELLED/TIMED_OUT` | `REJECT` | terminal 任务不能恢复 |

revision 检查发生在状态矩阵之前。例如调用方看到 `COMPLETED@revision=0`，但数据库已经是
`revision=1`，结果必须先报版本冲突，而不是根据旧认知返回 terminal 拒绝。

### 工程性设计说明

`AgentResumeService.evaluate()` 是纯评估边界：

```text
读取 Snapshot
  → 校验 expectedRevision
  → 根据 status 和 interruptId 生成 typed decision
```

它故意不在 `evaluate()` 中直接 `return store.save(...)`。`return` 只把 Java 对象交给调用方，
不会产生持久化；真正保存必须显式调用 Store。当前切片不保存，是为了把“决定下一步”和
“执行有副作用的下一步”分开：

- M0-S2 负责 Snapshot restore 与终态修复。
- M0-S3 负责 Interrupt 的 CAS 一次性消费。
- M0-S4 负责并发执行权 Claim。

如果现在把三件事塞进一个方法，单元测试通过也无法证明并发 Resume 安全，并且会跨越单
切片限制。

### 测试结果

已执行确认：

- 目标测试：`AgentResumeServiceTest`，通过。
- checkpoint 相关回归：通过；PostgreSQL/Testcontainers 因本机 Docker 不可用而跳过。
- 全量回归：116 tests，0 failures，0 errors，4 skipped。

尚未验证：

- PostgreSQL 下的 revision/CAS 行为。
- 两个进程并发 Resume 的唯一执行权。
- Interrupt 一次性消费。
- Snapshot restore 后继续运行 Agent Loop。

这些不是 M0-S1 的交付，后续涉及并发与数据库语义时必须用 PostgreSQL/Testcontainers
验证，不能用当前 H2 结果代替。

### 面试问题

#### 问题一：为什么 Resume Command 需要 expectedRevision？

参考回答：

Resume 是基于持久化状态作出的并发决策。客户端读取 revision N 后，任务可能已被另一个
请求推进到 N+1。携带 expectedRevision 可以把旧写或旧决定转成明确的版本冲突，防止重复
消费 Interrupt 或重复取得执行权。

#### 问题二：为什么 Result 不直接返回 boolean？

参考回答：

“能恢复”并不代表可以立刻运行。运行态下一步是 Claim，等待输入态下一步是消费
Interrupt，terminal 才是拒绝。typed result 把这些分支建模成协议，避免控制器根据字符串
或布尔值猜测下一动作，也方便后续审计和测试。

#### 问题三：为什么 M0-S1 的 evaluate 不直接保存？

参考回答：

本步只定义恢复决策。CAS 保存、Interrupt 消费和执行权 Claim 各自有不同并发不变量，必须
在独立切片中实现并使用 PostgreSQL 验证。把它们放在一个方法里会模糊事务边界，也很难
判断失败发生在“决定前”还是“副作用后”。

#### 问题四：为什么 terminal 状态一律拒绝 Resume？

参考回答：

terminal 状态表示任务生命周期已经结束。允许通用 Resume 会破坏终态单调性，可能重复
执行工具。若需要修复 terminal Step，只能走 M0-S2 定义的受限修复语义，并产生新的
revision，而不是重新执行原 Handler。

#### 问题五：`toolId` 为什么不能代替 `toolCallId` 去重？

参考回答：

`toolId` 只表示工具种类。同一个 `git_commit` 在一个任务里可能合法调用多次；按
`toolId` 去重会把不同参数、不同业务意图的调用误认为同一次。`toolCallId` 才标识一个
逻辑调用，重试保持它不变，新参数生成新 ID。

### 下一切片

按执行计划停止在 M0-S1。下一步是 `M0-S2：实现 Snapshot 恢复与终态修复`，不会在本轮
继续实现。

---

## 2026-07-29：M0-S2 Snapshot 恢复与终态修复

### 本步目标

本步进入 `IMPLEMENT` 模式，只完成执行计划中的 `M0-S2`：

- 从指定 revision 的 Snapshot 恢复新的 `AgentState`。
- 普通 `RUNNING` Snapshot 从持久化的 `nextStep` 继续。
- 最后一个已提交 Step 是 terminal action、但任务仍为 `RUNNING` 时补写生命周期 revision。
- 不重新执行已经存在于 Snapshot 中的 Step Handler。

本步不消费 Interrupt、不取得并发执行权、不增加 Controller、不修改模型协议，也不实现
Tool Ledger。

### 为什么存在“terminal Step 已保存，但任务仍 RUNNING”

当前 Runner 与 Checkpoint Lifecycle 的执行顺序是：

```text
执行 terminal action
  → 把 Step 加入 AgentState
  → currentStep + 1
  → stepCommitted：保存 RUNNING revision N
  → 设置 stopReason / finalAnswer
  → completed：保存最终状态 revision N+1
```

如果进程在两次保存之间崩溃，数据库会留下：

```text
status    = RUNNING
nextStep  = 已越过 terminal Step
last Step = FINAL_ANSWER 或 ASK_CLARIFICATION
```

这不是重新执行 terminal Handler 的理由，因为 Action 和 Observation 已经完整提交。缺失的
只是外层任务状态 revision。

### 新增源码

```text
src/main/java/com/koawa/agent/agent/checkpoint/AgentSnapshotRecoveryResult.java
src/main/java/com/koawa/agent/agent/checkpoint/AgentSnapshotRecoveryService.java
src/test/java/com/koawa/agent/agent/checkpoint/AgentSnapshotRecoveryServiceTest.java
src/test/java/com/koawa/agent/agent/checkpoint/PostgresAgentSnapshotRecoveryServiceTest.java
```

现有 `AgentTaskSnapshotMapper`、`AgentLoopRunner`、`AgentCheckpointStore` 和
`PersistentAgentCheckpointLifecycle` 已有未提交改动，本步没有覆盖这些文件。

### Recovery Result

```java
public record AgentSnapshotRecoveryResult(
        AgentTaskSnapshot snapshot,
        AgentState state,
        Outcome outcome
) {
}
```

`Outcome` 有三个分支：

| Outcome | 含义 |
| --- | --- |
| `READY_TO_CONTINUE` | Snapshot 为 RUNNING，最后 Step 非 terminal，可从 nextStep 继续 |
| `TERMINAL_STEP_REPAIRED` | 已根据最后一个 terminal Step 补写生命周期 revision |
| `NOT_RUNNING` | Snapshot 已不是 RUNNING，本服务不继续推进 |

Result 保证：

```text
snapshot.taskId == state.taskId
snapshot.nextStep == state.currentStep
READY_TO_CONTINUE 只允许对应 RUNNING Snapshot
```

`shouldContinue()` 只是 `Outcome` 的便捷判断，不会取得执行权。真正并发 Claim 仍属于
M0-S4。

### Recovery Service 流程

```text
restore(taskId, expectedRevision)
  → 读取 Snapshot
  → 校验实际 revision
  → Mapper 创建新的 AgentState
  → status 不是 RUNNING：NOT_RUNNING
  → RUNNING 且最后 Step 非 terminal：READY_TO_CONTINUE
  → RUNNING 且最后 Step terminal：补写 lifecycle revision
```

普通恢复不保存：

```text
Snapshot revision N
nextStep = 2
steps    = [step 0, step 1]

恢复后：
AgentState.currentStep = 2
AgentState.steps       = [step 0, step 1]
```

Runner 下一次只能规划 `step 2`，不会重新调用 `step 0/step 1` 的 Handler。

### FINAL_ANSWER 修复

```text
revision N
status = RUNNING
last action = FINAL_ANSWER
last observation.content = "done"
```

恢复服务重建：

```text
stopReason = FINAL_ANSWER
finalAnswer = "done"
status = COMPLETED
revision = N + 1
```

原 Steps、`nextStep`、任务身份和 `createdAt` 保持不变。

### ASK_CLARIFICATION 修复

`ASK_CLARIFICATION` 是 Agent Loop 的 terminal action，但不是任务生命周期的 terminal
status。修复结果是：

```text
stopReason = ASK_CLARIFICATION
status = WAITING_FOR_INPUT
revision = N + 1
pendingInterrupt = 新的 USER_INPUT Interrupt
```

本步只恢复等待点，不消费用户回复。一次性消费留给 M0-S3。

### 为什么修复必须使用原 revision CAS

修复保存使用：

```java
store.save(repairedSnapshot, current.revision());
```

假设读取的是 revision N，但保存前其他进程已经写入 N+1，CAS 必须失败，不能用旧
AgentState 覆盖新 Snapshot。

修复时间也保证不早于原 `updatedAt`，避免系统时钟回拨导致持久化时间倒退。

### “不重新执行 Handler”的准确边界

本步能够保证：

```text
Step 已存在于 Snapshot
  → Mapper 恢复该 Step
  → currentStep 使用 nextStep
  → Runner 不重新执行该 Step Handler
```

本步不能保证：

```text
工具已经产生外部副作用
  → Step 尚未成功保存
  → 进程崩溃
```

后一种场景在 Snapshot 中没有已提交 Step 证据，仍然不能安全自动重放。这正是 R003 和
ADR-002 要求未来 M5-S4 Tool Ledger 解决的问题。

### 测试结果

已执行确认：

- `AgentSnapshotRecoveryServiceTest`：4 tests，全部通过。
- checkpoint 包相关回归：通过。
- 全量回归：121 tests，0 failures，0 errors，5 skipped。

PostgreSQL 验证：

- 新增 `PostgresAgentSnapshotRecoveryServiceTest`。
- 当前机器没有可用 Docker，1 个新增 PostgreSQL 用例与原有 4 个 PostgreSQL 用例均跳过。
- 因此本轮没有宣称 PostgreSQL 终态修复已经实际通过；测试会在 Docker/CI 可用时运行。

尚未验证：

- 两个执行者并发 Resume 时的唯一执行权。
- Interrupt 一次性消费。
- REST API 重启恢复 E2E。
- 工具成功但 Step 保存失败后的副作用恢复。

### 面试问题

#### 问题一：为什么 Snapshot 已有 terminal Step，任务状态仍可能是 RUNNING？

参考回答：

Step 保存和任务终态保存是两个持久化边界。Runner 先保存完整 Step，再根据 terminal
action 设置 stopReason 并保存任务状态。两次写入之间崩溃会留下已提交 terminal Step 和
RUNNING 状态。这是可修复的不完整生命周期投影，不应该重新执行 Handler。

#### 问题二：为什么恢复时使用 nextStep，而不是 steps.size() + 1？

参考回答：

`nextStep` 是 Snapshot 明确定义的恢复游标，并且当前 Snapshot 不变量要求
`steps.size() == nextStep`。Runtime 使用持久化游标而不是重新猜测，可以避免 off-by-one
和历史 Step 重放；未来 Snapshot 协议变化时也能在 Mapper 边界迁移。

#### 问题三：终态修复为什么必须新增 revision？

参考回答：

RUNNING revision N 与 COMPLETED revision N+1 表示两个不同的持久化事实。原地修改 revision
N 会破坏审计和 CAS 语义，也会让拿着 revision N 的并发调用无法发现状态已经改变。

#### 问题四：为什么终态修复不调用原 terminal Handler？

参考回答：

Snapshot 已经保存了 terminal Action 和 Observation，说明该 Step 的运行结果已经提交。
修复只从 Observation 重建 stopReason、finalAnswer 或 Pending Interrupt。重新调用 Handler
既没有必要，也可能重复外部副作用。

#### 问题五：M0-S2 是否已经解决所有重复工具调用？

参考回答：

没有。它只跳过已经提交到 Snapshot 的 Step。如果工具成功但 Step 保存失败，Snapshot
仍停留在旧 nextStep，单靠 Step Checkpoint 无法判断副作用是否发生。该崩溃窗口需要稳定
toolCallId、执行前后 Ledger、幂等或结果查询处理。

### 下一切片

按执行计划停止在 M0-S2。下一步是 `M0-S3：实现澄清 Interrupt 消费`，本轮不继续实现。

## 2026-07-29：M0-S3 澄清 Interrupt 一次性消费

### 本切片目标

本切片只闭合 `WAITING_FOR_INPUT → RUNNING` 的持久化边界，不取得执行权，也不直接运行
Agent Loop：

```text
AgentResumeCommand
  → 校验 taskId + expectedRevision + interruptId + userInput
  → 加载 WAITING_FOR_INPUT Snapshot
  → 恢复同一 AgentState
  → 把用户回复追加到 historySnapshot
  → 清除上一次 ASK_CLARIFICATION 的停止字段
  → CAS 保存 RUNNING revision N + 1
  → 清除 pendingInterrupt
```

M0-S4 才负责对这个 RUNNING 任务取得唯一执行权。

### Command 新增 userInput

`AgentResumeCommand` 新增可空字段：

```java
public record AgentResumeCommand(
        String taskId,
        long expectedRevision,
        String interruptId,
        String userInput
) {
}
```

保留三参数构造器，使 M0-S1 已有的普通 RUNNING Resume 调用不需要立刻改写。语义是：

- RUNNING Resume 不使用 `userInput`。
- `evaluate()` 保持 M0-S1 的职责，只读校验 status、revision 和 interruptId，不保存
  Snapshot。
- `consume()` 强制要求匹配的 interruptId 和非空 userInput，并执行一次性持久化。

### 保存动作在哪里

真正保存发生在 `AgentInterruptConsumptionService.consume()`：

```java
AgentTaskSnapshot saved = store.save(
        next,
        current.revision()
);
```

这里的 `return` 仍然只负责返回保存结果：

```java
return new AgentInterruptConsumptionResult(
        interrupt.interruptId(),
        saved,
        state
);
```

因此顺序是“先 CAS 保存成功，再返回 Result”，不是“通过 return 自动落库”。

### 为什么用户回复写入 historySnapshot

用户回复是后续模型推理需要读取的对话事实，所以追加为标准 USER 消息：

```java
history.add(ChatMessage.user(userInput));
state.setHistorySnapshot(history);
```

这样恢复后的 `AgentRequestAssembler` 可以按原有历史消息通道把回复交给模型。没有把回复塞进
`Map<String, String> recoveryContext`，因为后者适合小型恢复控制数据，不适合承担完整对话
存储。

本切片仍沿用 M0 的 Snapshot v1。后续上下文体系成熟后，再把长期对话、RunItem 和
Checkpoint 投影拆开；当前不跨切片重构存储模型。

### CAS 如何实现一次性消费

假设客户端读取到：

```text
taskId = task-1
revision = 7
status = WAITING_FOR_INPUT
interruptId = interrupt-abc
```

第一次请求使用 `expectedRevision = 7`，保存：

```text
revision = 8
status = RUNNING
pendingInterrupt = null
history += USER reply
```

第二次重复提交仍携带 `expectedRevision = 7`。Store 已经是 revision 8，因此抛出
`CheckpointConflictException`，未来 REST 层映射为 HTTP 409。第二次请求不会再追加消息，
也不会再推进 revision。

错误 interruptId、缺少 userInput、任务不存在或状态不是 WAITING_FOR_INPUT，也都在保存前
失败，原 Snapshot 保持不变。

### 为什么还需要 consumedUserInputStep

第一次定向测试暴露出一个重要的恢复歧义：

```text
最后一个 Step = ASK_CLARIFICATION
status = RUNNING
pendingInterrupt = null
```

这个形状可能表示两件完全不同的事：

1. terminal Step 已保存，但 WAITING_FOR_INPUT 生命周期 revision 尚未保存，属于 M0-S2
   应修复的崩溃窗口。
2. WAITING_FOR_INPUT 已经存在，用户回复也已消费，任务正准备继续，不能再次退回等待态。

两种情况都要求保留原 Steps，仅靠 `lastStep.isTerminal()` 无法区分。因此消费 revision 在
`recoveryContext` 中写入一个轻量边界标记：

```text
consumedUserInputStep = 0
```

恢复服务只在“最后一步是 ASK_CLARIFICATION 且其 stepIndex 等于该标记”时返回
`READY_TO_CONTINUE`。如果没有标记或 stepIndex 不匹配，仍按 M0-S2 规则修复为
WAITING_FOR_INPUT。

这个字段记录的是持久化边界，不是用户回复正文。使用 stepIndex 而不是单纯的 boolean，
是为了避免后面出现第二个 ASK_CLARIFICATION 时误用旧标记；只有被消费的那个澄清 Step
会匹配。

### 为什么要清除停止字段

等待态 Snapshot 中通常包含：

```text
stopReason = ASK_CLARIFICATION
finalAnswer = 澄清问题文本
```

用户已经回答后，任务重新进入 RUNNING，这两个值如果继续保留，会让调用方误认为任务仍在
停止。因此消费时清除：

```java
state.setStopReason(null);
state.setFinalAnswer(null);
state.setFailureType(null);
state.setErrorMessage(null);
```

`originalQuestion`、taskId、conversationId、userId、已完成 Steps、nextStep、maxSteps、
deadlineAt、createdAt 和 planningRecoveryAttempts 均保持。

### 测试结果

已执行确认：

- 定向单元测试：`AgentInterruptConsumptionServiceTest`、
  `AgentResumeServiceTest`、`AgentSnapshotRecoveryServiceTest` 全部通过。
- checkpoint 包完整回归：通过。
- 全量回归：128 tests，0 failures，0 errors，6 skipped。

覆盖的关键行为：

- 正确回复只消费一次，revision 增加 1，状态转为 RUNNING。
- 同一 taskId、原 Steps 和 nextStep 保持。
- 回复进入 historySnapshot，重启后仍能恢复并继续。
- 后续新的 ASK_CLARIFICATION 不会被旧 stepIndex 标记误判为已消费。
- 错误 interruptId 和缺失 userInput 不推进 Snapshot。
- 重复提交返回 revision conflict，不重复追加回复。
- 非 WAITING_FOR_INPUT 和不存在的任务被显式拒绝。

PostgreSQL 验证：

- 新增 `PostgresAgentInterruptConsumptionServiceTest`，覆盖 JDBC JSON 往返、revision CAS、
  重启恢复和重复提交冲突。
- 当前机器没有可用 Docker，该用例被 Testcontainers 跳过。
- 因此本轮不能宣称 PostgreSQL 上的一次性消费已经实际验证；Docker/CI 可用后需要运行该
  测试。

2026-07-30 收口复验：

- M0-S3 定向测试：18 tests，0 failures，0 errors，1 skipped。
- checkpoint 包回归：48 tests，0 failures，0 errors，6 skipped。
- 全量回归：127 tests，0 failures，0 errors，6 skipped。
- Docker CLI 已安装，但本次复验无法连接 Docker Engine；6 个 PostgreSQL/Testcontainers
  测试被跳过。默认命令下真实 PostgreSQL 测试的可复现性留给 M0-S4a 收口。

尚未验证：

- 两个执行者消费成功后谁取得唯一执行权。
- Resume REST API 的 409 响应映射。
- PostgreSQL 上两个并发 Resume 请求的实际竞争。
- 用户回复后的完整模型调用 E2E。

### 面试问题

#### 问题一：为什么 interruptId 和 expectedRevision 两个都要校验？

参考回答：

`interruptId` 校验业务身份，防止用户回复投递给同一任务中的旧等待点；
`expectedRevision` 校验并发版本，防止客户端基于旧 Snapshot 覆盖新状态。前者不能发现
同一个 Interrupt 被并发消费，后者不能表达“回复的是哪个问题”，两者职责不同。

#### 问题二：为什么重复提交选择 409，而不是静默返回成功？

参考回答：

当前 Result 没有请求幂等键，服务无法严格证明第二个请求与第一个请求内容完全相同。CAS
冲突明确告诉客户端其前置状态已经过期，更安全。未来若引入稳定 resumeRequestId，可以
保存并返回第一次结果，形成真正的幂等成功。

#### 问题三：为什么用户回复放 historySnapshot，而不是 pendingInterrupt.context？

参考回答：

`pendingInterrupt` 表示尚未解决的等待条件，消费后必须清除；把回复留在其中会把“待处理”
和“已发生的对话事实”混在一起。historySnapshot 是后续模型请求的既有输入通道，也能在
重启后自然恢复。

#### 问题四：为什么消费成功后不立刻调用 Agent Loop？

参考回答：

消费 Interrupt 与取得执行权是两个不同的并发边界。当前步骤只通过 CAS 把输入持久化并将
状态变为 RUNNING；M0-S4 再通过 Claim/Lease 决定谁能执行。若在消费方法中直接运行，
进程崩溃、并发 Resume 和超时处理会耦合在一个难以验证的事务中。

#### 问题五：consumedUserInputStep 解决了什么歧义？

参考回答：

`ASK_CLARIFICATION Step` 和 `WAITING_FOR_INPUT` 状态是分两次保存的。如果进程在两次保存之间崩溃，
数据库会留下“`RUNNING` 且最后一步是 `ASK_CLARIFICATION`”，这表示用户还没回答。用户回答并消费 `Interrupt` 后，
任务也会重新变成“`RUNNING` 且最后一步是 `ASK_CLARIFICATION`”，但这次表示可以继续。两种状态外观相同，
所以用 `consumedUserInputStep` 记录具体哪个澄清 `Step` 已被回答。恢复时，`stepIndex` 匹配就继续，
不匹配或为空就重新进入等待；记录 `stepIndex` 还能避免旧回答误伤后续的新澄清问题。

### 下一切片

按执行计划停止在 M0-S3。下一步是 `M0-S4：执行权租约或 CAS Claim`，本轮不继续实现。

## 2026-07-30：M0-S4b Lease 领域协议与内存 Store

### 本切片目标

项目负责人批准 `ADR-003` 并要求先进入 Lease 主线，因此暂时跳过只处理
Docker/Testcontainers 兼容性的 M0-S4a。本切片只实现：

```text
AgentExecutionPermit
  → AgentExecutionLeaseStore
  → InMemoryAgentExecutionLeaseStore
  → 确定性时间与并发测试
```

本切片不增加数据库表，不实现 JDBC Lease Store，不接 Resume/Agent Loop，也不实现心跳
线程。

### Permit 表达什么

`AgentExecutionPermit` 是 Store 成功授予执行权后返回的不可变凭证：

```text
taskId         哪个任务
ownerId        哪一次执行尝试
fencingToken   这是第几代执行权
expiresAt      这份执行权何时失效
```

它的构造器保持 package-private。普通调用者只能从 `acquire()` 得到 Permit，不能直接传入一个
更大的 Token 来伪造执行权。

`toString()` 刻意不输出 `ownerId`。ownerId 用于内部所有权匹配，不是认证凭据，也不应该因
异常日志或调试输出被完整暴露。

### Store 协议

```java
AgentExecutionPermit acquire(
        String taskId,
        long expectedRevision,
        Duration leaseDuration
);

AgentExecutionPermit renew(
        AgentExecutionPermit permit,
        Duration leaseDuration
);

void release(AgentExecutionPermit permit);

Optional<AgentExecutionPermit> load(String taskId);
```

`acquire()` 不接收 ownerId。Store 使用随机 ID 生成器创建新的执行尝试身份；内存实现允许
注入 `Supplier<String>`，只是为了让测试可重复。

`load()` 会返回最近一条记录，即使它已经过期或被释放。原因是 Token 历史不能删除：
下一次 Acquire 必须从旧 Token 加 1，而不是重新从 1 开始。

### Acquire 如何流转

```text
校验 taskId、expectedRevision、leaseDuration
  → 加载 Checkpoint
  → Checkpoint 不存在：CheckpointNotFoundException
  → revision 不匹配：CheckpointConflictException
  → 原子检查当前 Lease
  → 未过期：AgentExecutionConflictException
  → 不存在：创建 token 1
  → 已过期/已释放：创建 token N + 1
  → 返回新的 Permit
```

内存 Store 使用 `ConcurrentHashMap.compute()`，因此同一个 taskId 的两个 Acquire 不会同时
创建成功。

### Renew 与 Release

续租和释放都必须匹配：

```text
taskId + ownerId + fencingToken
```

并且 Lease 必须仍然有效。失败时抛出
`AgentExecutionLeaseLostException`，原因分为：

- `LEASE_MISSING`：任务没有 Lease 记录。
- `OWNER_OR_TOKEN_MISMATCH`：执行权已经属于另一代或另一个 Owner。
- `LEASE_EXPIRED`：当前 Permit 已经过期，不能通过 Renew 复活。

`release()` 不删除记录，只把 `expiresAt` 缩短到当前时间。下一次 Acquire 因此可以立刻
接管，同时获得更大的 Token。

### Fencing 解决什么问题

示例：

```text
A acquire → token 1
A 暂停超过租期
B takeover → token 2
A 恢复并尝试 renew/release
→ OWNER_OR_TOKEN_MISMATCH
```

即使 A 和 B 在物理上短暂同时运行，数据库也只会承认 Token 2。M0-S4d 会把同样的检查
加入 Checkpoint Write，从而拒绝 A 的迟到状态写入。

### 为什么 Lease 不修改 Checkpoint revision

Checkpoint revision 表示任务业务进度；Lease 表示运行协调状态。如果每次心跳都更新
Snapshot：

- revision 会在没有完成任何 Step 时持续增长。
- Resume 客户端会频繁遇到无意义的 revision conflict。
- 任务恢复数据与 Worker 存活状态会被错误耦合。

因此内存 Store 独立保存 Lease，Acquire、Renew、Release 后原 Checkpoint revision 保持
不变。

### 内存实现的验证边界

`ConcurrentHashMap.compute()` 只能证明单 JVM 内同一 taskId 的 Lease 转换是原子的。
Checkpoint revision 的读取与 Lease map 更新不是一个跨 Store 事务，不能用它宣称
PostgreSQL 上的 Acquire 已经原子化。

M0-S4c 必须通过同一数据库事务或条件 SQL，把以下条件放进真实 PostgreSQL 语义中：

```text
checkpoint exists
checkpoint.revision == expectedRevision
lease missing or expired
```

### 测试结果

已执行确认：

- `InMemoryAgentExecutionLeaseStoreTest`：6 tests，0 failures，0 errors，0 skipped。
- checkpoint 包回归：54 tests，0 failures，0 errors，6 skipped。
- 全量回归：133 tests，0 failures，0 errors，6 skipped。

新增测试覆盖：

- 首次 Acquire 返回 Token 1。
- 未过期时第二个 Acquire 明确冲突。
- 当前 Owner 可以 Renew 和 Release。
- Release 后重新 Acquire 返回 Token 2。
- 过期后接管返回 Token 2。
- 旧 Permit 不能 Renew 或 Release 新 Lease。
- 两个并发 Acquire 只有一个成功。
- Acquire/Renew/Release 不修改 Checkpoint revision。
- Permit 的默认字符串表示不泄露 ownerId。

6 个 skipped 仍是现有 PostgreSQL/Testcontainers 用例；按项目负责人的本轮指令，没有处理
Docker 测试基础设施。

### 面试问题

#### 问题一：为什么 Checkpoint revision 不能代替 Lease？

参考回答：

revision CAS 只能在保存状态时发现冲突。两个 Worker 可能已经同时读取 revision 7，并在
任何一个保存前都调用了模型或工具。Lease 把冲突提前到进入执行阶段之前；后续 Fenced
Write 再防止租约过期后的旧 Worker 写回。

#### 问题二：为什么 Lease 需要过期，而不是永久 Claim？

参考回答：

永久 Claim 在持有进程崩溃后不会自动释放，任务会永久卡住。可续租 Lease 把“持有者仍然
存活”变成有限时间承诺；心跳停止后，其他 Worker 可以在到期后接管。

#### 问题三：为什么同时需要 ownerId 和 fencingToken？

参考回答：

ownerId 区分同一代执行权由哪个执行尝试持有；fencingToken 表达执行权代数。接管后 Token
严格递增，因此旧 Worker 即使恢复并继续持有旧对象，也会因为 Token 落后而被拒绝。

#### 问题四：为什么 Release 不直接删除 Lease？

参考回答：

删除会丢失上一代 Token。下一次 Acquire 如果重新从 Token 1 开始，旧 Worker 的 Token 1
可能再次与当前值相同。保留记录并把它标记为过期，可以让下一代稳定递增到 Token 2。

#### 问题五：Lease 能保证外部工具 Exactly Once 吗？

参考回答：

不能。Lease 能阻止旧 Worker 更新 KoawaAgent 内部状态，但不能撤销已经发出的 HTTP、
Shell、Git 或 MCP 操作。外部副作用仍需要稳定 toolCallId、幂等键、结果查询、Tool Ledger
或 `OUTCOME_UNKNOWN` 人工处理。

### 下一切片

下一步是 `M0-S4c`：增加 Flyway V2 和 JDBC Lease Store，把 Acquire/Renew/Release 与
Checkpoint revision 条件落实到 PostgreSQL。M0-S4b 到此停止，不提前接 Agent Loop。

## 2026-07-30：M0-S4b-r Lease 子包整理

### 本切片目标

随着 Lease 协议加入，原 `agent.checkpoint` 根包同时承载 Snapshot、Resume、Interrupt 和
执行权协调，职责开始混杂。本切片只进行包结构移动：

```text
agent.checkpoint.lease
├── AgentExecutionPermit
├── AgentExecutionLeaseStore
└── InMemoryAgentExecutionLeaseStore
```

测试同步移动到：

```text
src/test/.../agent/checkpoint/lease
```

异常仍保留在统一的 `agent.exception` 包；Snapshot、Resume 和 Interrupt 本轮不移动，避免
把纯整理扩大成跨模块重构。所有 Lease 业务逻辑保持不变。

### 为什么按职责边界分包

这三个概念虽然都与恢复有关，但回答的问题不同：

```text
Snapshot  保存“执行到哪里”
Resume    决定“如何恢复”
Lease     决定“谁有权继续执行”
```

Lease 后续还会增加 JDBC Store、心跳和 Fenced Write。提前建立 `checkpoint.lease` 边界，
可以避免这些协调类继续堆入 Checkpoint 根包，也让依赖方向更清楚。

### 测试结果

- Lease 定向测试：6 tests，0 failures，0 errors，0 skipped。
- 全量回归：133 tests，0 failures，0 errors，6 skipped。
- `git diff --check`：通过。

### 面试问题

#### 为什么不是简单地按 model、service、impl 分包？

参考回答：

`model/service/impl` 只描述技术形态，容易把不同业务边界的类重新混在一起。按 Snapshot、
Resume、Lease 分包能够直接表达不同的一致性职责，使 JDBC Lease Store 不会依赖 Resume
用例，也避免 Lease 心跳污染 Snapshot revision。

### 下一切片

下一步仍是 `M0-S4c`：在 `checkpoint.lease` 中增加 JDBC Lease Store，并通过 Flyway V2
增加独立 Lease 表。

## 2026-07-30：M0-S4c PostgreSQL Lease Store

### 本切片目标

本切片把 M0-S4b 的 Lease 协议落实到独立数据库表和 JDBC Store：

```text
Flyway V2
  → agent_execution_lease
  → JdbcAgentExecutionLeaseStore
  → PostgreSQL 并发、过期、接管测试
```

本切片仍不接 Resume/Agent Loop，不启动心跳，也不实现 Permit-aware Checkpoint Write。

### 为什么使用独立表

V2 新增：

```text
agent_execution_lease
├── task_id             PK + FK → agent_checkpoint
├── owner_id
├── fencing_token
├── lease_expires_at
└── updated_at
```

`task_id` 是主键，因此一个任务最多只有一条 Lease 记录。外键使用 `ON DELETE CASCADE`：
管理员明确删除 Checkpoint 时，对应 Lease 一并清理；正常 Release 不删除 Lease 行。

Lease 没有加入 Snapshot JSON。这样 Renew 不会制造新的 Checkpoint revision，旧 Snapshot
也不需要升级 schemaVersion。

### Acquire 为什么是一条 SQL

核心结构：

```sql
INSERT INTO agent_execution_lease (...)
SELECT ...
FROM agent_checkpoint
WHERE task_id = ?
  AND revision = ?
ON CONFLICT (task_id) DO UPDATE
SET owner_id = EXCLUDED.owner_id,
    fencing_token = current_lease.fencing_token + 1,
    lease_expires_at = EXCLUDED.lease_expires_at
WHERE current_lease.lease_expires_at
        <= statement_timestamp()
RETURNING ...;
```

这条语句同时表达：

```text
Checkpoint 必须存在
Checkpoint revision 必须匹配
Lease 不存在时插入 Token 1
Lease 已过期时原子接管并令 Token + 1
Lease 未过期时不返回记录
```

不能在 Java 中先 `SELECT Lease` 再 `INSERT/UPDATE`，否则两个数据库连接可能同时看到“没有
Lease”，随后都尝试取得执行权。PostgreSQL 的唯一键冲突处理和条件更新把竞争放在数据库
中完成。

Acquire 只持有单条 SQL 的短事务，不跨模型或工具调用占用数据库连接。

### 为什么使用数据库时间

Acquire、Renew 和 Release 都使用：

```sql
statement_timestamp()
```

Worker 只传 `leaseDuration`，不传“当前时间”。这样多台机器即使系统时钟有偏差，数据库仍
使用同一个时间源判断 Lease 是否过期。

首版 JDBC 精度为毫秒：

```text
expiresAt = statement_timestamp()
            + leaseDurationMillis
```

小于 1 毫秒的 Duration 被明确拒绝。Store 返回的 Permit 使用数据库 `RETURNING` 提供的
真实 `lease_expires_at`，不是 Worker 本地推算值。

### Renew

Renew 使用条件更新：

```sql
UPDATE agent_execution_lease
SET lease_expires_at = databaseNow + duration,
    updated_at = databaseNow
WHERE task_id = ?
  AND owner_id = ?
  AND fencing_token = ?
  AND lease_expires_at > databaseNow
RETURNING ...;
```

必须同时匹配 Owner 和 Token，且 Lease 仍未过期。过期 Lease 不能通过 Renew 复活，只能
停止当前 Worker，由新的 Acquire 产生更大的 Token。

### Release

Release 不删除行，而是：

```sql
SET lease_expires_at = statement_timestamp()
```

下一次 Acquire 可以立即接管，并基于保留的 Token 历史执行 `Token + 1`。Release 同样要求
Owner、Token 和未过期状态匹配，旧 Permit 不能释放新 Worker 的 Lease。

### 失败分类

Acquire 没有返回 Permit 时，Store 查询最新数据库状态并分类：

- Checkpoint 不存在：`CheckpointNotFoundException`。
- revision 不匹配：`CheckpointConflictException`。
- Checkpoint 匹配但存在 Lease：`AgentExecutionConflictException`。

Renew/Release 影响 0 行时：

- Lease 行不存在：`LEASE_MISSING`。
- Owner 或 Token 不匹配：`OWNER_OR_TOKEN_MISMATCH`。
- Owner 和 Token 匹配但条件更新失败：`LEASE_EXPIRED`。

失败后的补充查询用于产生可诊断异常；真正决定是否取得或保持执行权的是前面的原子条件
SQL，不能根据补充查询结果绕过并重新写入。

### PostgreSQL 测试设计

新增 5 个真实 PostgreSQL 场景：

1. Acquire、Renew、Release 后再次 Acquire 返回 Token 2，Checkpoint revision 不变化。
2. 任务不存在、revision 过期、Lease 未过期分别返回对应冲突。
3. 强制 Lease 过期后允许接管，旧 Permit 的 Renew/Release 被 Fencing 拒绝。
4. 两个独立 Acquire 并发竞争时只有一个成功。
5. 删除 Checkpoint 时通过外键级联清理 Lease。

过期测试直接修改数据库过期时间，不使用 `sleep(30s)`，避免慢测试和时间抖动。

### 已执行确认

- Java 生产代码和 PostgreSQL 测试代码编译通过。
- `KoawaAgentApplicationTest` 通过，Flyway 在 H2 验证环境中从 V1 成功迁移到 V2。
- 全量回归：138 tests，0 failures，0 errors，11 skipped。

### 尚未验证

- 新增的 5 个 `PostgresJdbcAgentExecutionLeaseStoreTest` 因当前 Docker 不可用被跳过。
- 因此本轮不能宣称 Acquire SQL、PostgreSQL 并发接管或数据库时间语义已经在本机实际
  执行确认。
- 现有另外 6 个 PostgreSQL/Testcontainers 测试也继续跳过。

根据项目负责人的指令，本轮不回到 M0-S4a 处理 Docker/Testcontainers 环境，保持上述限制
并继续主线。

### 面试问题

#### 问题一：为什么 Acquire 要使用 INSERT SELECT？

参考回答：

`INSERT SELECT` 只有在对应 Checkpoint 存在且 revision 匹配时才产生待插入行，因此任务
资格检查与 Lease 写入处于同一条数据库语句中。若先在 Java 中查询 revision，再单独写
Lease，中间会留下状态变化窗口。

#### 问题二：为什么不用 SELECT FOR UPDATE 后一直持锁？

参考回答：

模型和工具调用可能持续数十秒甚至数分钟。跨调用持有行锁和数据库事务会长期占用连接、
增加死锁与故障恢复成本。Lease 只在 Acquire/Renew/Release 时执行短事务，运行期间通过
持久化过期时间表达所有权。

#### 问题三：为什么由数据库计算 expiresAt？

参考回答：

多个 Worker 的本地时钟可能漂移。如果 A 用自己的时钟写过期时间、B 用另一台机器的时钟
判断是否过期，会产生提前接管或延迟恢复。数据库时间使所有权判断使用单一时间源。

#### 问题四：为什么 Release 也必须检查 fencingToken？

参考回答：

A 的 Lease 过期后 B 可能已经以更大的 Token 接管。如果 A 的迟到 Release 只按 taskId
更新，就会把 B 的 Lease 误标记为过期。Owner 和 Token 条件确保 Release 只能作用于调用者
持有的那一代执行权。

#### 问题五：为什么 PostgreSQL 测试不能由 H2 代替？

参考回答：

本设计依赖 PostgreSQL 的 `ON CONFLICT DO UPDATE ... WHERE`、并发唯一键竞争、
`statement_timestamp()`、`RETURNING` 和时区精度。H2 能验证迁移结构和 Spring 启动，但
不能证明这些 PostgreSQL 并发与时间语义。

### 下一切片

下一步是 `M0-S4d`：增加 Permit-aware Checkpoint Write、Lease Session/Heartbeat 和
Lease Lost 停止边界。M0-S4c 到此停止，不提前连接 Resume 入口。

## 2026-07-30：M0-S4d Fenced Write 与 Lease Heartbeat

### 本切片目标

本切片完成 Lease 取得之后的两道保护：

```text
后台 Heartbeat
  → 维持当前 Permit
  → 续租失败记录 Lease Lost

Checkpoint Boundary
  → revision CAS
  → ownerId + fencingToken + 数据库过期时间
  → 任一不满足则拒绝写入
```

本切片不组合 Resume Claim，不增加 REST API，也不修改 Snapshot JSON 或数据库结构。

### Permit-aware Checkpoint Write

新增 `AgentFencedCheckpointWriter`，与普通 `AgentCheckpointStore` 分开。普通 Store 继续支持
首次任务和既有非 Resume 路径；恢复执行必须显式传入 `AgentExecutionPermit`。

JDBC 实现的核心条件位于同一条 `UPDATE`：

```sql
WHERE current_checkpoint.task_id = ?
  AND current_checkpoint.revision = ?
  AND EXISTS (
      SELECT 1
      FROM agent_execution_lease AS lease
      WHERE lease.task_id = current_checkpoint.task_id
        AND lease.owner_id = ?
        AND lease.fencing_token = ?
        AND lease.lease_expires_at > statement_timestamp()
      FOR UPDATE
  )
```

因此不存在“先在 Java 中检查 Lease，随后 Lease 被接管，但旧 Worker 仍完成 Checkpoint
UPDATE”的窗口。`FOR UPDATE` 会锁住匹配的 Lease 行，使旧写入与新 Token 接管按数据库
锁顺序串行化；只有 `EXISTS` 条件而不锁行仍会留下交错窗口。

失败诊断有明确优先级：如果 revision 和 Token 同时过期，优先返回
`AgentExecutionLeaseLostException`。旧 Worker 不能把执行权丢失当成普通 CAS 冲突后自动
重试。只有 Permit 仍有效时，revision 不匹配才是 `CheckpointConflictException`。

内存实现用于确定性组件测试。它明确记录：Lease Store 检查和 Checkpoint Store CAS 不是
跨 Map 原子操作，不能用来证明分布式 Fencing；数据库保证只来自 JDBC 单语句实现。

### Lease Session 与 Heartbeat

`AgentExecutionLeaseSession` 持有最新 Permit，并用守护线程按配置间隔调用 `renew()`：

```text
start
  → scheduleWithFixedDelay
  → renew current Permit
  → 保存数据库返回的新 expiresAt
  → 失败后停止继续续租
```

`renewInterval` 必须小于 `leaseDuration`，两者最小精度为 1 毫秒。Session 不使用 Worker
本地时间判断数据库 Lease 是否过期。

任何非预期 Renew 异常都会转换成：

```text
AgentExecutionLeaseLostException
reason = RENEWAL_FAILED
```

这样数据库短暂不可用时，Worker 不会假定自己仍然持有执行权。Session 在下一个
Checkpoint 安全边界通过 `requireActive()` 抛出保存的失败。

`close()` 会停止 Heartbeat，并尽力 Release 最新 Permit。Release 失败只记录
`taskId + fencingToken`，不输出完整 ownerId，也不会覆盖已经形成的业务结果。

### Runtime 停止边界

`PersistentAgentCheckpointLifecycle` 新增 Lease Session 路径：

```text
stepCommitted/completed
  → session.requireActive()
  → session.currentPermit()
  → AgentCheckpointService.save(..., permit)
  → AgentFencedCheckpointWriter
```

普通新任务路径保持原行为，不要求 Permit。`AgentLoopRunner` 对
`AgentExecutionLeaseLostException` 直接向上抛出，不转换成普通 `ERROR`，也不会尝试写入一个
虚假的 FAILED Checkpoint。

### 已执行确认

- M0-S4d 非 PostgreSQL 定向测试：14 tests，0 failures，0 errors，0 skipped。
- 全量回归：149 tests，0 failures，0 errors，13 skipped。
- 新增覆盖：
  - 当前 Permit + 当前 revision 可以写入。
  - 当前 Permit 下保留普通 revision conflict。
  - 过期 Permit 不推进 revision。
  - Token 和 revision 同时过期时优先 Lease Lost。
  - Heartbeat 更新 Session 中的最新 expiresAt。
  - Renew 基础设施失败转换为 `RENEWAL_FAILED`。
  - Session Close 释放最新 Permit。
  - Agent Loop 不吞掉 Lease Lost。

### 尚未验证

- `PostgresJdbcAgentExecutionLeaseStoreTest` 已增加 2 个 Fenced Write 场景，但当前 Docker
  不可用，因此该类 7 个测试全部 skipped。
- 全量 13 个 skipped 均为 PostgreSQL/Testcontainers 测试。
- 因此 JDBC 条件 UPDATE 的真实 PostgreSQL 语义仍属于尚未在本轮环境执行确认的边界。

### 面试问题

#### 问题一：为什么 Fencing 校验必须和 Checkpoint Update 是同一条 SQL？

参考回答：

如果先查询 Lease 再更新 Checkpoint，Lease 可以在两个语句之间过期并被接管。旧 Worker
虽然通过了第一次检查，仍可能在新 Worker 之后写入。把 Lease 条件放进 UPDATE，数据库只在
该语句执行时仍满足 Token 和有效期的情况下修改 Checkpoint。

#### 问题二：为什么同时过期时优先报告 Lease Lost，而不是 revision conflict？

参考回答：

revision conflict 对合法执行者可能是可重载、可重试的并发错误；Lease Lost 表示调用者已经
没有执行资格，必须停止。若旧 Worker 收到普通 CAS 冲突后重新加载并重试，就可能绕过
Fencing 的安全意图。

#### 问题三：Heartbeat 失败为什么不能继续“乐观执行”？

参考回答：

Worker 无法确认数据库中的 Lease 是否仍属于自己。继续调用模型或工具可能与新持有者并行
产生成本和副作用。因此先记录 Lease Lost，并在下一个安全边界停止；最终 Checkpoint 写仍由
数据库 Fencing 兜底。

#### 问题四：为什么 Release 失败不能覆盖原业务结果？

参考回答：

Release 是缩短 Lease 恢复时间的清理动作，不是正确性的唯一来源。即使 Release 失败，
Lease 仍会自然过期。若 close 异常覆盖已经完成的业务结果，调用者反而无法区分任务失败和
清理失败。

#### 问题五：为什么内存 Fenced Writer 不能证明分布式安全？

参考回答：

它先从 Lease Map 读取再写 Checkpoint Map，两者不是一个数据库原子操作。它适合验证异常
分类和调用链，但只有 PostgreSQL 中包含 Lease EXISTS 条件的单条 UPDATE 才能证明真实
Fencing 窗口被关闭。

### 下一切片

下一步是 `M0-S4e`：组合 Resume Evaluate/Interrupt Consume、Acquire、Snapshot Restore、
Lease Session 和 Fenced Lifecycle，验证两个并发 Resume 只有一个进入执行阶段。

## 2026-07-31：M0-S4e Resume Claim 组合用例

### 本切片目标

本切片把此前分散的 Resume 能力组合成一个应用用例：

```text
RUNNING
  → Evaluate
  → Acquire(expectedRevision)
  → Restore
  → Start Lease Session
  → 返回可执行上下文

WAITING_FOR_INPUT
  → Evaluate
  → Consume Interrupt（revision N → N + 1）
  → Acquire(revision N + 1)
  → Restore
  → Start Lease Session
  → 返回可执行上下文
```

本切片仍不运行 Agent Loop，不增加 REST Controller，不修改 Snapshot JSON、Flyway、
Provider 或 Tool Ledger。M0-S5 的 API 层只需要消费本用例的明确结果，不应自行重新拼装
Evaluate、Consume、Acquire 顺序。

### 为什么增加 AgentResumeClaimService

此前每个服务只负责一个局部动作：

- `AgentResumeService`：只读判断当前状态是否允许 Resume。
- `AgentInterruptConsumptionService`：消费 USER_INPUT Interrupt，并把任务推进到新的
  RUNNING revision。
- `AgentExecutionLeaseStore`：基于 taskId + expectedRevision 取得执行权。
- `AgentSnapshotRecoveryService`：从 Snapshot 恢复可变 `AgentState`。
- `AgentExecutionLeaseSession`：续租并记录 Lease Lost。
- `PersistentAgentCheckpointLifecycle`：在 Step/终态边界执行 fenced write。

如果由 Controller 逐个调用这些服务，RUNNING 和 WAITING 两条路径很容易出现顺序差异，
也容易忘记失败后的 Lease 清理。`AgentResumeClaimService` 将顺序固定为一个应用层用例，
但它不把 HTTP、模型调用或 Agent Loop 塞进 Checkpoint 模块。

核心分支如下：

```java
AgentResumeResult decision = resumeService.evaluate(command);
if (!decision.accepted()) {
    return new AgentResumeClaimResult.Rejected(decision);
}

long claimRevision = switch (decision.nextAction()) {
    case ACQUIRE_EXECUTION_CLAIM -> decision.revision();
    case CONSUME_USER_INPUT_INTERRUPT ->
            consumptionService.consume(command).snapshot().revision();
    case REJECT -> throw new IllegalStateException(...);
};
return claimRunning(command.taskId(), claimRevision);
```

WAITING 路径不能继续拿旧 revision 申请 Lease。Interrupt 消费已经产生一个包含用户回复的
新 RUNNING Snapshot，因此 Acquire 必须校验这个新 revision；否则执行者恢复到的状态可能
不包含刚提交的用户输入。

### 三种显式结果

`AgentResumeClaimResult` 使用 sealed interface 表达三个互斥结果：

| 结果 | 含义 | 是否可进入 Agent Loop |
|---|---|---|
| `Claimed` | 已恢复 State，并持有活跃 Lease Session | 是 |
| `Rejected` | 状态矩阵拒绝了当前 Resume 命令 | 否 |
| `Recovered` | 恢复过程补齐了遗漏的 terminal 状态 | 否 |

`Recovered` 不能与 `Rejected` 合并。例子：

```text
revision 7:
  status = RUNNING
  lastStep = FINAL_ANSWER

Resume:
  Acquire token 4
  → 恢复时发现 terminal Step
  → fenced repair 为 revision 8 / COMPLETED
  → Release token 4
  → 返回 Recovered
```

这个请求不是因为状态矩阵不允许而被拒绝；它实际完成了一次必要的持久化修复，但不应再
进入 Agent Loop。

### AgentClaimedExecution 的职责

`AgentClaimedExecution` 把以下三个必须一起使用的对象绑定起来：

```text
恢复后的 AgentState
  + 恢复来源 AgentTaskSnapshot
  + 使用同一 Lease Session 的 Fenced Checkpoint Lifecycle
```

它实现 `AutoCloseable`。未来 API/运行编排应使用：

```java
try (AgentClaimedExecution execution = claimed.execution()) {
    execution.requireActive();
    // 使用 execution.state() 和 execution.checkpointLifecycle()
}
```

关闭上下文会停止 Heartbeat，并尽力 Release Lease。对象不暴露
`AgentExecutionPermit`，因此上层拿不到 ownerId，也不能绕过 Session 直接拼装一个 Permit。

### 恢复修复为什么也要 Fencing

`AgentSnapshotRecoveryService` 原有的 terminal repair 会写一次 Checkpoint。如果组合
Resume 已经先 Acquire，但 repair 仍调用普通 `AgentCheckpointStore.save()`，就会出现：

```text
Worker A 取得 token 8
  → A 的 Lease 过期
Worker B 取得 token 9
  → A 才执行 terminal repair
```

此时只有 revision CAS 不足以证明 A 仍有写入资格。因此 Recovery 新增 Permit-aware
重载；只要由 Resume Claim 调用，terminal repair 就通过
`AgentFencedCheckpointWriter` 同时验证 revision、owner、token 和 Lease 有效期。旧的无
Permit 重载继续保留，兼容 M0-S2 的独立恢复测试和非 Resume 调用。

### 失败清理边界

Acquire 成功后的任一步骤都可能失败，例如 Snapshot 在加载时发生 revision conflict、
恢复映射失败，或 Session 配置非法。组合服务遵守：

```text
Acquire 成功
  → 尚未创建 Session 时失败：直接尽力 Release Permit
  → Session 已创建后失败：关闭 Session
  → Release 失败：记录 taskId + fencingToken，不覆盖原异常
```

业务拒绝发生在 Acquire 之前，因此不会创建 Lease。`Claimed` 返回后，Lease 生命周期转交
给 `AgentClaimedExecution`，由调用者关闭。

### 配置

新增两个可绑定的运维配置，默认值来自 ADR-003：

```yaml
agent:
  checkpoint:
    execution:
      lease-duration: ${AGENT_LEASE_DURATION:30s}
      renew-interval: ${AGENT_LEASE_RENEW_INTERVAL:10s}
```

`renewInterval` 必须小于 `leaseDuration`，两者至少为 1 毫秒。30/10 秒只是默认运维值，
测试仍使用显式配置，不等待真实 Lease 周期。

### 并发证明

内存组合测试使用两个线程和两个 Latch 同时发起相同的：

```text
taskId = concurrent-task
expectedRevision = 0
```

测试保留成功方的 `AgentClaimedExecution`，使其 Lease 在另一个请求竞争时仍然有效。最终
结果必须严格为：

```text
1 × AgentResumeClaimResult.Claimed
1 × AgentExecutionConflictException
```

没有使用 `sleep()` 推测竞争窗口。

另增加真实 PostgreSQL/Testcontainers 组合测试。两个 Worker 使用独立
`JdbcTemplate/JdbcAgentExecutionLeaseStore`，验证相同 Resume 只有一个进入执行上下文。

### 已执行确认

- `AgentResumeClaimServiceTest`：6 tests，0 failures，0 errors，0 skipped。
- 相关 Resume/Recovery/Lease/Fenced Lifecycle：40 tests，0 failures，0 errors，
  0 skipped。
- 全量回归：156 tests，0 failures，0 errors，14 skipped。
- Spring Context 已成功创建 JDBC Lease Store、Fenced Writer、Resume 组件和组合服务。

### 尚未验证

- `PostgresAgentResumeClaimServiceTest` 的 1 个真实 PostgreSQL 并发用例已编译，但当前
  Docker 不可用，因此被 skipped。
- 全量 14 个 skipped 均为 PostgreSQL/Testcontainers 用例。
- 因此本轮已通过内存组件测试证明组合顺序和结果分类，但不能宣称真实 PostgreSQL 的完整
  并发 Resume 已在本机执行确认。
- 按项目负责人此前指令，本切片不回到 M0-S4a 处理中断主线的 Docker 兼容性问题。

### 面试问题

#### 问题一：为什么 Evaluate 通过后还必须在 Acquire 中再次校验 revision？

参考回答：

Evaluate 是状态矩阵判断，不授予执行权。它读取 revision 7 后，其他请求可能已经把
Checkpoint 推进到 revision 8。Acquire 必须把 expectedRevision 放进原子 Claim 条件；
否则一个基于过时状态作出决定的请求仍可能取得 Lease。

#### 问题二：WAITING_FOR_INPUT 为什么先 Consume，再 Acquire？

参考回答：

用户输入属于需要持久化的业务状态。Consume 通过 CAS 一次性清除 Interrupt、追加用户消息，
并产生新的 RUNNING revision。随后对新 revision Acquire，保证执行者恢复的正是包含这次
输入的状态。两个相同提交并发时，也会先在 Consume CAS 上淘汰一个。

#### 问题三：为什么 AgentClaimedExecution 要实现 AutoCloseable？

参考回答：

它表示一项有生命周期的资源所有权，而不只是普通 DTO。成功返回后 Heartbeat 已启动，
调用者必须在完成、失败或取消时停止续租并 Release。AutoCloseable 允许用
try-with-resources 把清理路径固定下来，避免 Controller 的多个 return/exception 分支漏掉
Release。

#### 问题四：为什么不把 Permit 直接返回给 Controller？

参考回答：

Permit 中包含 ownerId、fencingToken 和有效期，是内部执行权凭据。Controller 只需要
AgentState 和受 Fence 保护的生命周期。封装 Permit 可以减少泄露 ownerId、使用旧 Permit
写入、或绕过 Heartbeat Session 的机会。

#### 问题五：为什么 terminal repair 也必须使用 Fenced Write？

参考回答：

terminal repair 同样会推进业务 revision。只要这次写发生在 Resume Acquire 之后，它就
属于某一代 Worker 的执行结果。如果只做 revision CAS，Lease 过期后的旧 Worker仍可能在新
Worker 接管前后提交修复；Fencing 才能证明写入者仍持有当前执行权。

#### 问题六：并发测试为什么要让成功方保持未关闭？

参考回答：

如果第一个请求一返回就关闭并 Release，第二个请求可能合法取得更大的 Token，测试结果会变
成两个顺序成功，而不是验证“活跃执行期间只有一个 Worker”。保留成功方上下文直到两个请求
都完成，才能准确覆盖未过期 Lease 冲突。

### 下一切片

M0-S4 到此完成。下一阶段是 `M0-S5`：Task 查询、Resume REST API、错误映射、真实
PostgreSQL 重启恢复 E2E 和会话历史持久化。该范围明显大于单切片文件上限，开始前应先按
API 合同、运行编排、PostgreSQL E2E、Conversation Store 拆分为可独立验收的子切片。

## 2026-07-31：M0-S5a Resume 运行编排

### 本切片目标

上一切片的 `AgentResumeClaimService` 已经能完成：

```text
Evaluate
  → Consume（仅 WAITING_FOR_INPUT）
  → Acquire Lease
  → Recovery
  → 返回 AgentClaimedExecution
```

但 `Claimed` 只代表“已取得执行资格”，还没有真正进入 `AgentLoopRunner`。本切片补齐
Claim 后的运行编排，使恢复状态、Fenced Checkpoint Lifecycle 和 Lease 清理在一次执行中
保持同一所有权边界。

本切片不实现 REST Controller、Task Query、Conversation Store 或新的数据库表，避免把
API 合同、运行语义和持久化扩展混入同一次提交。

### 核心调用链

新增 `AgentResumeExecutionService`，调用顺序固定为：

```text
resume(command)
  → claimService.claim(command)
      ├─ Rejected  → 返回 Rejected，不进入 Agent Loop
      ├─ Recovered → 返回 Recovered，不进入 Agent Loop
      └─ Claimed
          → try (AgentClaimedExecution)
          → requireActive()
          → runner.run(state, fencedLifecycle)
          → fencedLifecycle.completed(terminalState)
          → 返回 Executed(AgentRunResult)
          → close：停止 Heartbeat，并尽力 Release Lease
```

`try-with-resources` 同时覆盖正常返回和异常路径。Planner、Action Executor、
`stepCommitted` 或 `completed` 任一处抛出异常时，执行上下文仍会关闭，避免 Heartbeat
继续续租或 Lease 一直占用。

### 为什么 Runner 要支持每次执行传入 Lifecycle

`AgentLoopRunner` 是 Spring 单例，其构造器中的默认 Lifecycle 服务于普通 Chat 路径。
Resume 的 Lifecycle 则携带本次 Lease Session 的 ownerId 与 fencingToken，只能属于本次
Claim。

因此新增重载：

```java
run(AgentState state, AgentCheckpointLifecycle executionLifecycle)
```

原有 `run(state)` 仍委托给构造时的默认 Lifecycle，不改变 Chat 行为。Resume 必须显式传入
`execution.checkpointLifecycle()`，从而防止恢复任务错误地使用普通 CAS 写入，或复用另一
次执行的 Fencing 身份。

### 为什么 Runner 返回后还要调用 completed

`AgentLoopRunner` 在每个 Step 提交后调用 `stepCommitted`，但它只负责循环，不负责判断
上层要用哪种持久化所有权完成终态边界。普通 Chat 由 `DefaultAgentChatService` 调用
`completed`；Resume 则必须由持有 Fenced Lifecycle 的运行编排服务调用。

所以一次最终回答会有两次有意义的写入：

```text
初始 snapshot: revision 1，已有 Step 0，RUNNING

执行 Step 1 / FINAL_ANSWER
  → stepCommitted
  → revision 2，Step 1 已保存

退出 Agent Loop
  → completed
  → revision 3，任务状态 COMPLETED
```

这两次写入不是重复保存。revision 2 证明 Step 结果已经持久化；revision 3 证明整个任务的
终态边界已经持久化。二者都使用同一个 Lease Session 的 Fenced Lifecycle。

### 运行结果为什么分三类

新增的 `AgentResumeExecutionResult` 是封闭结果类型：

- `Executed`：取得 Claim 并实际运行 Agent Loop，返回 `AgentRunResult`。
- `Rejected`：状态矩阵或 revision 前置条件拒绝，请求没有进入执行阶段。
- `Recovered`：恢复阶段已修复 terminal boundary，请求完成了必要写入，但不应再次运行
  Agent Loop。

这样 Controller 后续可以穷尽映射三类结果，而不需要从 `null`、异常或布尔值猜测“到底有
没有运行”。

### Spring 组装

`AgentCheckpointConfiguration` 新增 `AgentResumeExecutionService` Bean，复用已有的
`AgentResumeClaimService` 与 `AgentLoopRunner`。本轮没有创建第二个 Runner，也没有把
Lease/Fencing 细节暴露到 Controller 层。

### 已执行确认

- 定向测试：`AgentResumeExecutionServiceTest` 与
  `AgentLoopCheckpointLifecycleTest`，8 tests，0 failures，0 errors，0 skipped。
- 相关 Resume/Recovery/Lease/Fenced Lifecycle 回归：49 tests，0 failures，
  0 errors，0 skipped。
- 全量回归：161 tests，0 failures，0 errors，14 skipped。
- Spring Context 成功创建新的 `AgentResumeExecutionService` Bean。
- 异常测试确认 Runner 失败时 Lease 会被 Release，且异常继续向上传播。
- Rejected/Recovered 测试确认两类结果都不会调用 Agent Loop。

### 尚未验证

- 全量 14 个 skipped 均为 PostgreSQL/Testcontainers 用例；当前 Testcontainers 未发现
  可用 Docker 环境。
- 本切片没有新增 SQL 或 PostgreSQL 专属语义，运行编排使用内存 Store 完成行为验证；但
  不能把本轮结果表述为真实 PostgreSQL Resume E2E 已验证。
- 尚未接入 HTTP API，因此还没有验证状态码、并发冲突错误体和序列化合同。

### 面试问题

#### 问题一：为什么 Resume 不能直接调用 `runner.run(state)`？

参考回答：

`runner.run(state)` 使用 Runner 构造时的默认 Lifecycle，而 Resume 的写入资格属于本次
Claim，包含 ownerId 和 fencingToken。若不显式传入本次执行的 Fenced Lifecycle，旧 Worker
可能绕过 Lease 校验用普通 CAS 写回，Fencing 就只停留在 Acquire 阶段，没有覆盖执行结果。

#### 问题二：为什么 `completed` 不直接放进 AgentLoopRunner？

参考回答：

Runner 负责 Step 循环和停止条件，Lifecycle 的所有权由调用场景决定。普通 Chat 和 Resume
的完成写入分别由不同上层服务负责，后者还绑定 Lease Session。把 `completed` 强塞进
Runner 会混淆循环职责，也难以表达初始化失败、恢复修复和不进入循环的结果。

#### 问题三：为什么 `AgentClaimedExecution` 要用 try-with-resources？

参考回答：

它持有的不只是状态，还代表活跃 Heartbeat 和 Lease 所有权。正常返回、Planner 失败、工具
失败或终态保存失败都必须停止续租并 Release。try-with-resources 把所有退出路径统一到
`close()`，比在多个 catch/return 分支手工清理更可靠。

#### 问题四：为什么 Recovered 不能继续进入 Agent Loop？

参考回答：

Recovered 表示 Snapshot 的最后一个 Step 已经是 terminal，只是任务状态还没同步到终态。
恢复服务已经用 Fenced Write 修复这个边界。若再次进入循环，会在一个已经完成的业务结果后
继续规划，可能重复调用模型或工具。

#### 问题五：为什么一个最终 Step 会产生两次 revision？

参考回答：

第一次写保存 Step 本身，解决“动作结果是否落库”；第二次写保存任务终态，解决“任务是否
完成”。崩溃可能发生在两次写之间，所以恢复逻辑必须能识别“terminal Step 已保存但状态仍
RUNNING”。把两次写拆开使这个故障窗口可观察、可修复，并让每次状态推进都有独立 CAS。

### 下一切片

建议进入 `M0-S5b`：先定义 Task Query 与 Resume HTTP API 的 DTO、状态码和错误映射，再用
Controller 测试锁定合同。运行编排已经具备，API 层不需要再直接操作 Lease、Permit 或
Fencing Token。

## 2026-08-01：M0-S5b Task Query HTTP API

### 本切片目标

Resume Command 要求调用者提交 `taskId + expectedRevision`，等待用户输入时还必须提交当前
`interruptId`。在没有 Task Query 的情况下，客户端刷新、Runtime 重启或并发写入后无法取得
权威 revision，也无法知道当前正在等待哪个 Interrupt。

因此本切片先建立只读控制面：

```text
GET Task
  → 取得最新 revision/status/pendingInterrupt
  → 下一切片 POST Resume
```

本切片不调用 `AgentResumeExecutionService`，不定义 Resume 请求体，不增加全局异常映射，
也不修改数据库表。

### HTTP 合同

新增两个端点：

```http
GET /api/agent/v1/tasks/{taskId}
GET /api/agent/v1/conversations/{conversationId}/tasks
```

单任务存在时返回 `200 OK + AgentTaskView`，不存在时返回 `404 Not Found`。会话查询直接保持
`AgentCheckpointStore.list()` 的 `updatedAt DESC, taskId` 顺序；当前 M0 合同返回完整数组。

示例控制面响应：

```json
{
  "taskId": "task-1",
  "conversationId": "conversation-1",
  "revision": 7,
  "status": "WAITING_FOR_INPUT",
  "nextStep": 2,
  "maxSteps": 8,
  "deadlineAt": "2026-08-01T01:10:00Z",
  "pendingInterrupt": {
    "interruptId": "interrupt-7",
    "type": "USER_INPUT",
    "prompt": "Please provide a module name",
    "createdAt": "2026-08-01T01:01:00Z"
  },
  "createdAt": "2026-08-01T01:00:00Z",
  "updatedAt": "2026-08-01T01:02:00Z"
}
```

### 为什么不直接返回 AgentTaskSnapshot

`AgentTaskSnapshot` 是恢复协议，不是公共 API DTO，其中包含：

```text
userId
originalQuestion
steps[].thought
steps[].actionArgumentsJson
steps[].observationMetadataJson
historySnapshot
recoveryContext
pendingInterrupt.context
schemaVersion
```

这些字段可能包含用户内容、模型内部文本、工具参数或仅供恢复使用的元数据。直接序列化会把
Snapshot schema 与 HTTP schema 锁死，并可能因未来增加恢复字段而意外扩大 API 暴露面。

`AgentTaskView` 因此采用字段白名单，只暴露客户端驱动任务生命周期所需的数据。HTTP 合同
测试显式断言上述内部字段不存在，而不是依赖开发者记得添加 Jackson ignore 注解。

### AgentTaskView 不变量

公共 View 不只是任意 JSON 容器，它继续保持关键状态不变量：

```text
revision >= 0
0 <= nextStep <= maxSteps
maxSteps > 0
WAITING 状态必须存在 pendingInterrupt
非 WAITING 状态不得存在 pendingInterrupt
WAITING_FOR_INPUT 只能对应 USER_INPUT
WAITING_FOR_APPROVAL 只能对应 APPROVAL
updatedAt >= createdAt
```

`PendingInterruptView` 只保留 `interruptId/type/prompt/createdAt`，不复制内部 `context`。

### 查询服务与 Controller 的职责

```text
AgentTaskController
  → 只处理路径和 HTTP 200/404

AgentTaskQueryService
  → 校验并规范化查询 ID
  → 调用 AgentCheckpointStore.load/list
  → Snapshot 映射为 AgentTaskView

AgentCheckpointStore
  → 继续负责读取最新持久化 Snapshot
```

Controller 不直接依赖 JDBC Store，也不在 API 层挑选 Snapshot 字段。这样下一切片 Resume
结束后可以再次调用 Query Service，返回数据库中的权威 revision/status，而不是从运行时对象
猜测最终持久化版本。

### 已执行确认

- 定向测试：`AgentTaskQueryServiceTest` 与 `AgentTaskControllerTest`，8 tests，
  0 failures，0 errors，0 skipped。
- 相关 Task/Snapshot/Store/Spring Context 回归：32 tests，0 failures，0 errors，
  0 skipped。
- 全量回归：169 tests，0 failures，0 errors，14 skipped。
- Spring Context 成功创建 `AgentTaskQueryService` 和 `AgentTaskController`。
- MockMvc 已确认单任务 200、缺失任务 404、会话列表顺序和 JSON 字段白名单。

### 尚未验证与限制

- 全量 14 个 skipped 均为既有 PostgreSQL/Testcontainers 用例；当前 Docker 环境仍不可用。
- 本切片只复用已有 Store 查询，不增加 SQL、事务、CAS 或并发语义，因此没有新增
  PostgreSQL 专属结论。
- 会话任务列表目前没有分页；任务量增长后的分页协议和索引性能尚未设计。
- 当前项目没有认证上下文，端点尚未实施 taskId/conversationId 所有权校验。字段白名单降低
  暴露面，但不能替代认证授权；在接入真实多用户环境前必须补齐。
- 当前 Task View 是控制面视图，不返回完整 Step、对话历史或最终答案详情。

### 面试问题

#### 问题一：为什么 Resume API 之前先实现 Task Query？

参考回答：

Resume 使用 expectedRevision 做乐观并发控制，等待输入时还要校验 interruptId。客户端必须先
读取最新任务状态，才能构造有意义的 Resume Command。否则刷新或重启后只能猜 revision，
冲突也无法恢复成“重新读取再提交”的正常流程。

#### 问题二：为什么不能直接把 AgentTaskSnapshot 作为 HTTP Response？

参考回答：

Snapshot 是内部恢复协议，包含 history、recoveryContext、thought 和工具参数。把它直接暴露
会泄漏内部数据，并把持久化 schema 与公共 API 绑定。独立 View 使用字段白名单，让两种协议
能够分别演进。

#### 问题三：字段白名单比 `@JsonIgnore` 好在哪里？

参考回答：

`@JsonIgnore` 是黑名单：Snapshot 新增字段时默认可能被序列化，开发者必须记得继续排除。
独立 View 是白名单：只有明确复制的字段才会进入 API，新恢复字段默认不可见，泄漏风险和
协议耦合更低。

#### 问题四：列表为什么不在 Query Service 里重新排序？

参考回答：

Store 合同已经定义 latest-first，并由内存/JDBC 实现保证。Query Service 只做一对一映射并
保持顺序，避免同一排序语义在数据库和 Java 中重复实现。未来分页时，排序必须继续下推到
数据库，才能保证跨页稳定。

#### 问题五：返回 404 是否意味着任务一定从未存在？

参考回答：

不一定。当前 404 只表示最新 Checkpoint Store 中没有这个 taskId，可能是从未创建，也可能
是管理员执行了 retention delete。API 不应凭空区分 Store 无法证明的原因；若未来需要审计
删除语义，应增加 tombstone 或独立审计记录。

### 下一切片

建议进入 `M0-S5c`：实现 `POST /api/agent/v1/tasks/{taskId}/resume`。请求体映射为
`AgentResumeCommand`，调用 `AgentResumeExecutionService`，再使用本切片的 Query Service
读取权威任务状态，明确映射 `Executed/Rejected/Recovered`，但不在同一切片扩展 PostgreSQL
E2E 或 Conversation Store。

## 2026-08-01：M0-S5c Resume HTTP API

### 本切片目标

M0-S5a 已经完成 Resume 运行编排，M0-S5b 提供了查询最新 revision 和 Interrupt 的只读
控制面。本切片把两者连接成同步 HTTP 命令：

```http
POST /api/agent/v1/tasks/{taskId}/resume
Content-Type: application/json

{
  "expectedRevision": 7,
  "interruptId": "interrupt-7",
  "userInput": "module-a"
}
```

`interruptId` 和 `userInput` 在恢复 RUNNING 任务时可以为空；恢复 `WAITING_FOR_INPUT` 时由既有
Resume/Consumption 领域服务实施上下文相关校验。

本切片只固定成功执行、业务拒绝和恢复修复三类结果合同。Checkpoint 不存在、revision 冲突、
Lease 冲突和 Lease 丢失等异常的统一错误体留给 M0-S5d。

### HTTP 调用链

```text
POST Resume
  → Bean Validation(expectedRevision)
  → ResumeRequest 映射为 AgentResumeCommand
  → AgentResumeExecutionService.resume(command)
      ├─ Executed
      ├─ Rejected
      └─ Recovered
  → AgentTaskQueryService.findByTaskId(taskId)
  → 读取持久化后的权威 Task View
  → 映射 HTTP 状态与公共响应
```

HTTP Controller 不接触 Lease、Permit、Heartbeat、Fencing Token、Snapshot Mapper 或 Runner。
这些执行细节继续封装在 M0-S4/M0-S5a 的应用服务中。

### 为什么执行后重新查询 Task

`AgentRunResult` 描述本次运行结果，但不包含 Checkpoint revision 和完整持久化状态。如果
Controller 根据运行前的 command revision 推算响应，会遇到：

```text
WAITING revision 7
  → Consume User Input：revision 8
  → Step Commit：revision 9
  → Completed：revision 10
```

revision 增长次数取决于实际路径，不能写成固定 `expectedRevision + 1`。因此无论结果是
Executed、Rejected 还是 Recovered，Controller 都通过 Query Service 再读一次最新 Snapshot，
返回真正持久化的 `AgentTaskView`。

### 三类公共响应

新增 `AgentResumeResponse` sealed interface。它与内部的
`AgentResumeExecutionResult` 分离，避免把恢复对象、运行时 State 或内部决策结构直接序列化。
HTTP 层还定义独立的 `RejectionReason` 与 `RecoveryOutcome` 枚举并显式映射。这样领域枚举
未来增加内部状态时，不会自动扩大公共 JSON 合同。

#### Executed

```text
HTTP 200 OK
outcome = EXECUTED
task = 持久化后的 AgentTaskView
runResult = 本次 Agent Loop 的结果
```

示例：

```json
{
  "outcome": "EXECUTED",
  "task": {
    "taskId": "task-1",
    "revision": 10,
    "status": "COMPLETED"
  },
  "runResult": {
    "stopReason": "FINAL_ANSWER",
    "content": "Completed module-a"
  }
}
```

#### Rejected

```text
HTTP 409 Conflict
outcome = REJECTED
task = 当前权威 AgentTaskView
rejectionReason = 固定业务拒绝原因
```

它表示请求与当前任务状态冲突，例如 Interrupt ID 不匹配或任务已经终态。公共
`RejectionReason` 根本不定义 `NONE`，避免出现名为 Rejected、实际却没有拒绝原因的矛盾
结果。

#### Recovered

```text
HTTP 200 OK
outcome = RECOVERED
task = 修复后的权威 AgentTaskView
recoveryOutcome = TERMINAL_STEP_REPAIRED 等
```

Recovered 不是失败：请求没有再次调用模型或工具，但 Runtime 完成了必要的 terminal boundary
修复，所以返回 200。响应 record 禁止 `READY_TO_CONTINUE`，因为可继续的恢复必须进入
Agent Loop 并最终成为 Executed。

### 为什么响应不是一个带大量 nullable 字段的 record

候选的单 DTO 可能是：

```text
outcome
task
runResult?
rejectionReason?
recoveryOutcome?
```

它允许 `EXECUTED + rejectionReason` 或 `REJECTED + runResult` 等非法组合。三个 record 让 JSON
结构和 Java 类型同时表达互斥分支：

```text
Executed  只携带 task + runResult
Rejected  只携带 task + rejectionReason
Recovered 只携带 task + recoveryOutcome
```

每个 record 的紧凑构造器继续校验 outcome 与分支匹配。

### 请求校验

HTTP 请求使用：

```java
@NotNull @PositiveOrZero Long expectedRevision
```

这里刻意使用 `Long` 而不是 `long`。如果使用 primitive，JSON 缺少字段时 Jackson 会产生 0，
服务端无法区分“客户端明确提交 revision 0”和“客户端根本没有提交 revision”。使用 boxed
类型可让缺失字段保持 null，并由 Bean Validation 返回 400。

### 已执行确认

- 定向 `AgentResumeControllerTest`：5 tests，0 failures，0 errors，0 skipped。
- 相关 Resume/Task Query/Spring Context：41 tests，0 failures，0 errors，0 skipped。
- 全量回归：174 tests，0 failures，0 errors，14 skipped。
- MockMvc 已确认 Executed=200、Rejected=409、Recovered=200。
- 缺少或提交负数 expectedRevision 均返回 400，且不会调用运行服务或查询服务。
- 三种 JSON 只包含各自合法字段；矛盾的公共响应 record 会在构造时被拒绝。

### 尚未验证与限制

- 全量 14 个 skipped 仍为既有 PostgreSQL/Testcontainers 用例；当前 Docker 环境不可用。
- 本切片的 Controller 测试 Mock 了应用服务，证明 HTTP 映射合同，但不能替代真实 PostgreSQL
  Resume E2E。
- `CheckpointNotFoundException`、`CheckpointConflictException`、
  `AgentExecutionConflictException`、`AgentExecutionLeaseLostException` 等尚未映射为稳定公共错误
  DTO；目前依赖 Spring 默认异常处理，不视为 API 闭环。
- 端点是同步调用：HTTP 连接会保持到 Agent Loop 停止。长任务是否转为异步提交/轮询属于后续
  Runtime API 研究，不在 M0 中臆测实现。
- 项目仍没有认证上下文，Resume 尚未实施任务所有权校验，不能直接作为多租户生产 API。

### 面试问题

#### 问题一：为什么 Resume 返回前还要重新查询 Task？

参考回答：

一次 Resume 可能先消费 Interrupt，再保存多个 Step，最后保存终态，revision 不一定只增加 1。
AgentRunResult 也不携带持久化 revision。重新查询可以返回数据库中的权威状态，避免 Controller
复制 revision 推进规则。

#### 问题二：为什么 Recovered 返回 200，而不是 409？

参考回答：

Recovered 表示请求成功触发了幂等恢复修复，例如把 terminal Step 已保存但仍 RUNNING 的任务
修为 COMPLETED。它没有重新运行模型，但达成了合法目标，因此是成功结果，不是状态冲突。

#### 问题三：为什么 Rejected 返回 409？

参考回答：

请求 JSON 本身可以解析，但与当前资源状态不兼容，例如 interruptId 不是当前边界或任务已经
终态。这更接近资源状态冲突，而不是 400 语法错误。客户端应先 GET 最新 Task，再决定是否
重试或停止。

#### 问题四：为什么 expectedRevision 用 Long 而不是 long？

参考回答：

primitive long 在字段缺失时会得到默认 0，无法区分缺失和合法 revision 0。Long 保留 null，
配合 `@NotNull` 可以让缺失字段稳定返回 400，同时 `@PositiveOrZero` 拒绝负数。

#### 问题五：为什么不直接返回 AgentResumeExecutionResult？

参考回答：

ExecutionResult 是应用层内部协议，其中 Recovered 持有 Snapshot Recovery 对象，Rejected
持有领域决策，未来都可能因内部实现演进。独立 HTTP Response 只复制稳定字段，防止内部类型
变化无意破坏外部 JSON 合同。

### 下一切片

建议进入 `M0-S5d`：建立统一 `AgentApiError` 与 `@RestControllerAdvice`，锁定 not found、
validation、stale revision、active lease、interrupt consumption、lease lost 和 checkpoint
lifecycle failure 的 HTTP 状态、可重试提示及敏感字段边界。本切片不同时开始 PostgreSQL
重启 E2E。

## 2026-08-01：M0-S5d 统一 API 错误合同

### 本切片目标

M0-S5c 已经固定 Resume 的正常返回分支，但异常仍会落入 Spring 默认错误响应。默认响应会让
客户端依赖异常类名或文本，也可能把内部并发、持久化和租约细节暴露到 HTTP 边界。本切片新增
统一的 `AgentApiError` 与 `AgentApiExceptionHandler`，为 Agent API 固定：

- HTTP 状态：表达 HTTP 层的错误类别；
- `code`：供客户端稳定分支处理，不依赖可修改的英文消息；
- `taskId`：错误能安全定位任务时返回；
- `retryable`、`retryAt`：说明是否存在安全的恢复路径；
- `expectedRevision`、`actualRevision`：帮助客户端处理 CAS 冲突；
- `violations`：结构化返回请求字段错误。

本切片没有改变 Resume 状态机、Lease、Fencing、Checkpoint 保存流程，也没有开始 Conversation
Store。

### 公共错误结构

revision 冲突示例：

```json
{
  "code": "CHECKPOINT_REVISION_CONFLICT",
  "message": "Task revision has changed; reload before resuming",
  "taskId": "task-1",
  "retryable": true,
  "expectedRevision": 7,
  "actualRevision": 8,
  "violations": []
}
```

可选字段为 null 时不输出；`violations` 始终是非 null 数组，客户端无需同时处理 null 和空数组。
record 紧凑构造器还保证 code/message 合法、revision 非负，并对集合执行防御性复制。

### 异常映射矩阵

| 来源 | HTTP | 稳定 code | retryable | 公开的恢复信息 |
| --- | ---: | --- | --- | --- |
| Bean/方法参数校验失败 | 400 | `VALIDATION_FAILED` | false | 排序后的字段错误 |
| JSON 缺失或无法解析 | 400 | `MALFORMED_REQUEST` | false | 安全的固定消息 |
| Checkpoint 不存在 | 404 | `TASK_NOT_FOUND` | false | taskId |
| revision CAS 冲突 | 409 | `CHECKPOINT_REVISION_CONFLICT` | 发现 actual 时 true | expected/actual revision |
| 已有活跃执行 Lease | 409 | `TASK_EXECUTION_CONFLICT` | true | retryAt |
| 用户输入不再匹配当前 Interrupt | 409 | `INTERRUPT_CONSUMPTION_CONFLICT` | true | taskId |
| 执行期间丢失 Lease | 409 | `EXECUTION_LEASE_LOST` | false | taskId 与重新读取提示 |
| Checkpoint 生命周期保存失败 | 503 | `CHECKPOINT_PERSISTENCE_FAILED` | false | 安全的固定消息 |
| 已存 Snapshot 无法解码 | 500 | `CHECKPOINT_CORRUPTED` | false | taskId |
| 未分类异常 | 500 | `INTERNAL_ERROR` | false | 安全的固定消息 |

`retryable=true` 不表示客户端应原样无限重放请求。它表示存在明确恢复路径：revision/Interrupt
冲突先 GET 最新 Task，再用新边界构造请求；执行权冲突至少等到 `retryAt`。Lease 丢失和保存
失败被标为 false，因为模型或工具副作用是否发生可能未知，盲目重放会造成重复成本或重复副作用。

### Rejected 与 AgentApiError 的边界

`AgentResumeResponse.Rejected` 仍然是 Resume 应用服务正常返回的业务结果，例如任务已经终态或
当前 Interrupt 不接受这次输入。Controller 已获得一个合法结果，并能重新查询权威 Task View，
因此它继续返回带 `outcome=REJECTED` 的公共 Resume 响应。

`AgentApiError` 处理的是异常路径，例如请求无法解析、读取不到 Checkpoint、并发 CAS 竞争、
Lease 竞争或持久化失败。两者虽然都可能使用 HTTP 409，但语义不同：前者是状态机作出的显式
拒绝决定，后者是请求处理过程中抛出的错误。HTTP 状态不能取代响应体中的稳定类型。

### 为什么不能直接返回异常 message

内部异常常包含数据库错误、JSON 内容、ownerId、fencingToken、Lease 丢失原因或底层 cause。
这些数据既不是稳定 API，也可能泄露实现和敏感输入。因此 Advice 只返回预先定义的安全消息：

- Lease Lost 不公开 ownerId、fencingToken 和内部 reason；
- 持久化失败不公开 SQLException 或底层 cause；
- JSON 解析失败不回显 parser 的内部诊断和原始正文；
- 兜底 500 不公开异常 message、cause 或 stack trace。

当前兜底日志只记录异常类型，避免日志测试意外写入输入内容。这也意味着生产可观测性仍需后续
增加受控的 correlation/trace id 和结构化内部诊断，不能依赖向客户端暴露堆栈来排障。

### Task GET 的变化

`GET /api/agent/v1/tasks/{taskId}` 成功时仍直接返回 `AgentTaskView` 和 200；不存在时由 Controller
抛出 `CheckpointNotFoundException`，Advice 返回 404 + `TASK_NOT_FOUND`。HTTP 状态没有改变，
但空 404 变成了可供客户端稳定处理的错误合同。

### 已执行确认

- 编译：`mvn -q -DskipTests compile` 通过。
- 定向 API 测试：15 tests，0 failures，0 errors，0 skipped。
- 相关 Resume/Task Query/Lifecycle/Spring Context：59 tests，0 failures，0 errors，0 skipped。
- 全量回归：188 tests，0 failures，0 errors，14 skipped。
- MockMvc 已确认 malformed JSON 和字段校验均返回 400 稳定错误体。
- 测试已确认 Lease Lost 响应不包含 ownerId、fencingToken、reason、cause 或 stackTrace。
- 测试已确认 revision 冲突公开 expected/actual，活跃 Lease 冲突只公开 retryAt。

### 尚未验证与限制

- 全量 14 个 skipped 仍为既有 PostgreSQL/Testcontainers 用例；本机 Docker 环境当前不可用。
- 本切片没有新增数据库语义；异常映射主要由单元测试和 MockMvc 验证，不能替代真实 PostgreSQL
  Resume E2E。
- `CHECKPOINT_PERSISTENCE_FAILED` 的内部异常当前不携带安全 taskId，因此公共响应无法定位任务。
- 目前没有 correlation/trace id，也没有认证与任务所有权边界。
- 客户端退避算法、最大重试次数和幂等键还没有形成公共协议；`retryable` 只是服务端安全提示。

### 面试问题

#### 问题一：为什么同时需要 HTTP 状态和稳定错误 code？

参考回答：

HTTP 状态适合网关、监控和通用客户端判断大类，例如 400、404、409、500；但一个 409 可能是
revision 冲突、活跃 Lease、Interrupt 变化或 Lease Lost。稳定 code 才能让业务客户端选择重新
读取、等待 retryAt 或停止重试，同时避免依赖可修改的 message。

#### 问题二：为什么 Rejected 不统一改成 AgentApiError？

参考回答：

Rejected 是 Resume 状态机的合法结果，应用服务正常完成并返回了拒绝原因，Controller 还能
附带权威 Task View。AgentApiError 表达异常路径。保留两类协议可以区分“业务明确拒绝”和
“请求处理失败”，而不是把所有 409 压成同一种错误。

#### 问题三：为什么不向客户端返回原始异常 message 和 stack trace？

参考回答：

原始异常文本不是稳定合同，可能包含 SQL、内部类、ownerId、fencingToken、用户输入或凭据。
公共 API 应返回预定义的安全消息和结构化恢复信息；内部排障应依赖受控日志、指标和 trace id，
而不是把实现细节泄露给调用方。

#### 问题四：为什么 Lease Lost 的 retryable 是 false？

参考回答：

Lease Lost 往往发生在执行已经开始之后。旧 Worker 可能已经调用模型或外部工具，只是失去了
写回资格；此时原样重试可能重复成本或副作用。客户端应先重新读取权威任务状态，再由更高层
策略决定是否发起新命令，不能把它当成安全的自动重试。

#### 问题五：为什么 Checkpoint 保存失败返回 503，却仍标记 retryable=false？

参考回答：

503 表示当前服务未能可靠完成持久化，便于基础设施监控识别暂时性服务故障；但单次请求是否
可以安全重放取决于此前是否已经产生模型或工具副作用。HTTP 503 与请求级安全重试不是一回事，
所以在没有幂等键和副作用账本保证前保持 false。

#### 问题六：为什么字段错误要排序？

参考回答：

校验框架收集约束的顺序不应成为公共合同。按 field/message 排序让响应、快照测试和客户端日志
保持确定性，减少同一错误因遍历顺序不同而产生的无意义差异。

### 下一切片

M0 主线尚余真实 PostgreSQL 重启 E2E 和 Conversation Store 持久化。Docker 恢复后应优先完成
PostgreSQL Resume/重启闭环；若继续暂时绕开环境阻塞，下一切片可先收敛 Conversation Store 的
领域端口与持久化边界，但 PostgreSQL 行为在 Testcontainers 通过前只能标记为尚未验证。

## 2026-08-01：M0-S5e Conversation Store 领域端口

### 本切片目标

M0-S5d 完成 HTTP 错误边界后，M0 还缺少跨任务会话历史持久化。当前代码虽然存在只读端口
`AgentConversationHistoryLoader`，但写入端没有接口：

```text
DefaultAgentChatService
  → AgentConversationHistoryLoader（抽象读取）

AgentChatFacade
  → InMemoryAgentConversationStore（具体写入）
```

因此直接增加 JDBC 实现仍无法替换 Facade 的具体依赖。本切片先新增读写端口
`AgentConversationStore`，并锁定当前内存实现的基本不变量。它是下一步持久化替换点，但本切片
不新增数据库表，也不宣称重启后历史已经可恢复。

### 新的读写边界

```java
public interface AgentConversationStore
        extends AgentConversationHistoryLoader {

    void appendTurn(
            String conversationId,
            String userId,
            String question,
            String answer
    );
}
```

继承只读 Loader 的原因不是让所有消费者都依赖更大的接口。`DefaultAgentChatService` 仍只依赖
`AgentConversationHistoryLoader`，因为它只需要在创建任务前加载上下文；只有负责提交可交付
回复的 `AgentChatFacade` 依赖 `AgentConversationStore`。这保持了接口隔离：

```text
DefaultAgentChatService ──只读──→ AgentConversationHistoryLoader
                                      ↑
AgentChatFacade ──────────读写──→ AgentConversationStore
                                      ↑
                         InMemoryAgentConversationStore
```

Spring 中一个 `InMemoryAgentConversationStore` Bean 同时满足两个注入点。后续 JDBC 实现接入时，
Facade 不再需要知道数据来自内存还是 PostgreSQL。

### Turn 为什么必须成对追加

当前会话上下文按以下顺序保存：

```text
USER(question)
ASSISTANT(answer)
```

`appendTurn` 把二者视为一个逻辑单元。内存实现使用：

```java
conversations.compute(key, (ignored, existing) -> append(...));
```

对于同一个 key，`ConcurrentHashMap.compute` 会以一次映射更新发布新的不可变 List。并发读取者
只能看到旧 List 或包含完整 user/assistant 对的新 List，不会看到只追加 question 的半个 Turn。
这只是单进程内存原子性；未来 JDBC 必须用单行 Turn 或事务获得相同边界。

### 为什么改用 ConversationKey

旧实现用字符串拼接作为 Map key：

```java
normalize(userId) + ":" + normalize(conversationId)
```

它存在真实碰撞：

```text
userId="a:b", conversationId="c"   → "a:b:c"
userId="a",   conversationId="b:c" → "a:b:c"
```

两个不同会话会错误共享历史。新实现改为：

```java
private record ConversationKey(
        String conversationId,
        String userId
) {
}
```

record 的 `equals/hashCode` 分别比较两个字段，不依赖分隔符编码，所以不会出现上述歧义。数据库
切片也应保留独立的 `conversation_id`、`user_id` 列，而不是持久化拼接 key。

### 为什么 List.copyOf 仍然不够

`ChatMessage` 是带 setter 的可变类。下面只能保护 List 结构：

```java
List.copyOf(messages)
```

调用者虽然不能 `add/remove`，仍可以执行：

```java
loaded.get(0).setContent("changed");
```

如果 Store 返回内部元素引用，历史就会被外部修改。因此 `load()` 现在逐条创建新的
`ChatMessage(role, content)`，再对新 List 使用 `List.copyOf`：

```text
内部不可变 List + 内部 ChatMessage
              ↓ 逐元素复制
外部不可变 List + 外部 ChatMessage 副本
```

调用者可以修改自己的消息副本，但再次 load 仍得到原始内容。这里的“防御性复制”同时保护了
容器和可变元素边界。

### 20 条窗口为什么仍按完整 Turn 裁剪

内存基线继续保留最近 20 条消息，也就是最多 10 个完整 user/assistant Turn。每次只追加两条，
再从尾部截取最多 20 条，因此不会留下只有 answer 或只有 question 的半组消息。顺序保持
oldest-first，能直接交给现有上下文组装器。

本切片没有把 `20` 提升为长期公共协议。M6 Context Engine 最终应按 token/字符预算选择历史，
而不是永久依赖固定消息数量；当前窗口只是保持 M0 既有行为。

### 为什么没有立即按 taskId 去重

持久化 Store 最终需要幂等写入，但仅使用 `taskId` 作为唯一键会过早锁错语义：一个任务可以
多次 `ASK_CLARIFICATION`，用户回复后还可能产生最终答案，同一个 taskId 下可能存在多个逻辑
对话 Turn。如果现在规定“一个 taskId 只能写一次”，后续澄清消息会被误判为重复。

JDBC 设计必须先确定稳定 Turn identity，例如 `taskId + turn/step/revision boundary`，并同时决定：

- Checkpoint 已完成、Conversation 尚未追加时如何修复；
- 数据库提交成功但调用方收到失败时如何去重；
- 同一 Conversation 的并发 Task 按请求时间还是提交时间排序；
- Resume 产生的用户输入和最终回复由谁追加。

这些会改变 schema 和恢复语义，按治理规则必须在下一持久化切片先形成 ADR，不能在本端口切片
中臆测一个唯一键。

### 已执行确认

- 最窄测试：8 tests，0 failures，0 errors，0 skipped。
- 相关 Store/Facade/History Loader/Checkpoint Lifecycle/Spring Context：13 tests，
  0 failures，0 errors，0 skipped。
- 全量回归：196 tests，0 failures，0 errors，14 skipped。
- Spring Context 已确认同一个内存 Store 可以同时注入只读 Loader 和读写 Store。
- 测试已确认两个分隔符碰撞 key 相互隔离。
- 测试已确认只保留最近 10 个完整 Turn，并保持 oldest-first。
- 测试已确认修改 load 返回的 `ChatMessage` 不会污染后续读取。
- 测试已确认只有 `FINAL_ANSWER`/`ASK_CLARIFICATION` 的非空内容进入 Store。

### 尚未验证与限制

- 当前运行时仍装配 `InMemoryAgentConversationStore`；进程退出后历史仍然丢失。
- 没有新增 Flyway migration、JDBC 实现或 PostgreSQL 测试，本切片不涉及数据库行为结论。
- 全量 14 个 skipped 仍为既有 PostgreSQL/Testcontainers 用例；Docker daemon 当前未运行。
- Checkpoint 完成与 `appendTurn` 是两个独立动作，中间崩溃可能出现任务已完成但跨任务历史缺失。
- 当前 Resume HTTP 路径没有把恢复后的最终回复写入 Conversation Store。
- 内存 `compute` 只解决单 JVM 同 key 更新；不能证明多实例顺序或数据库事务语义。

### 面试问题

#### 问题一：为什么已经有 HistoryLoader，还要增加 ConversationStore？

参考回答：

HistoryLoader 是只读端口，适合只需要组装上下文的 AgentChatService；Facade 还需要追加 Turn，
此前只能依赖 InMemory 具体类。新增 Store 让写入也经过抽象边界，同时保留读消费者的最小接口，
后续才能在不修改业务调用方的情况下替换 JDBC 实现。

#### 问题二：为什么 AgentConversationStore 继承 Loader，而不是把 load 再写一遍？

参考回答：

Store 是 Loader 能力的超集，同一个实现需要同时支持读写。继承复用读取合同，但消费者仍按所需
能力依赖：只读服务声明 Loader，写入 Facade 声明 Store，避免所有组件都获得不需要的写能力。

#### 问题三：ConcurrentHashMap.compute 在这里保证了什么？

参考回答：

它保证同一个 ConversationKey 的 read-modify-write 映射更新是原子的，两个线程不会各自基于
旧 List 覆盖对方；新 List 一次发布，所以不会暴露半个 Turn。它不保证跨 JVM、数据库事务或
业务请求顺序，这些必须由持久化层单独解决。

#### 问题四：为什么字符串拼接不能作为复合 key？

参考回答：

当字段本身允许包含分隔符时，不同字段组合可能编码成同一字符串，导致租户或会话数据串线。
结构化 record 分别参与 equals/hashCode；数据库中也使用独立列和复合索引，避免不可靠编码。

#### 问题五：List.copyOf 为什么不能完全保护会话历史？

参考回答：

它只做浅复制并禁止修改 List 结构，元素 ChatMessage 仍是可变对象。调用者可通过 setter 修改
共享元素。因此 Store load 时需要逐元素复制；长期也可以把持久化边界映射到不可变 Message
Snapshot。

#### 问题六：为什么本切片不直接用 taskId 做幂等键？

参考回答：

一个任务可能跨越多次澄清和最终回答，taskId 不等于唯一对话 Turn。只按 taskId 去重会误吞
合法后续消息。应先定义稳定 Turn boundary，再用 taskId 与 step/revision 等组成幂等身份，并用
崩溃实验验证 Checkpoint 与 Conversation 两个写入之间的恢复策略。

### 下一切片

进入 `M0-S5f：Conversation Store 持久化设计`。由于它将改变 Flyway schema、幂等和崩溃恢复
语义，先以 DESIGN 模式起草 ADR，比较“每条 Message 一行”“每个 Turn 一行”和“从 Checkpoint
投影重建”至少三种方案，确定 Turn identity、排序、事务/修复和保留策略。ADR 批准后再拆 JDBC
实现与 PostgreSQL/Testcontainers 验证；不与真实 Resume 重启 E2E 混在同一提交。
