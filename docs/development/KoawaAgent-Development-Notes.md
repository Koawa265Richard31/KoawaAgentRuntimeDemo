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
