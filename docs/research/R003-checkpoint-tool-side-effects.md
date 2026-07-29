# R003：Checkpoint 与工具副作用

## 状态

`COMPLETE`

已经完成：

- R003-1：KoawaAgent 当前行为取证与最小崩溃实验。
- R003-2：LangGraph、Temporal、Restate 一手资料对照。
- R003-3：只读、幂等写、非幂等写的同故障点对照实验。
- R003-4：Step-only、幂等键、Ledger、结果查询和人工处理的方案对照。
- R003-5：已形成 ADR-002 草案、状态机、恢复矩阵和实施切片。

研究实验预算已经用完，ADR-002 已获人工批准并形成独立实施切片；生产 Ledger 尚未实现。

## 1. 研究问题

> 仅在完整 Step 边界保存 Checkpoint，能否保证 Coding Agent 在工具成功后崩溃并恢复时
> 不重复产生副作用？

这是一个可证伪问题。只要构造一次“工具成功、Step 尚未持久化、随后从旧 Snapshot
恢复”的场景，并观察到工具再次执行，就可以否定“Step Checkpoint 足以防止副作用重复”
这一假设。

## 2. 研究价值

这个问题直接影响：

- Patch、Git commit、发邮件和外部 MCP 写操作是否可能重复。
- Resume 是否能自动重放未完成 Step。
- 是否需要 Tool Execution Ledger、幂等键或 `OUTCOME_UNKNOWN`。
- Runtime 能否对外宣称安全恢复。

## 3. 当前 KoawaAgent 行为

### 已验证事实 E2：代码执行顺序

`AgentLoopRunner` 当前按以下顺序执行：

```text
Executor.execute
  → 得到 Observation
  → 构造 AgentStep
  → steps.add(step)
  → currentStep + 1
  → checkpointLifecycle.stepCommitted
```

因此存在一个时间窗口：

```text
外部工具已经成功
  → Step Checkpoint 尚未成功
```

`AgentCheckpointLifecycleException` 会向上传播，不会被改写为普通 Agent ERROR。这避免了
Runtime 在持久化状态未知时继续运行，但不能撤销已经发生的外部副作用。

### 已验证事实 E2：恢复数据来源

`AgentCheckpointService.load` 只从最新持久化 Snapshot 重建 `AgentState`。如果 Step
没有保存成功，恢复后的 `currentStep` 和 `steps` 仍来自旧 revision。

当前代码还没有 Tool Execution Ledger、稳定 `toolCallId` 或 `OUTCOME_UNKNOWN` 状态。

## 4. 参考对象

### 4.1 LangGraph

官方资料：

- Functional API：
  <https://docs.langchain.com/oss/python/langgraph/functional-api>
- Interrupts：
  <https://docs.langchain.com/oss/python/langgraph/interrupts>
- `Durability` 官方源码：
  <https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/types.py>
- PostgreSQL Checkpointer 官方源码：
  <https://github.com/langchain-ai/langgraph/blob/main/libs/checkpoint-postgres/langgraph/checkpoint/postgres/__init__.py>

本地源码快照：

```text
repository = langchain-ai/langgraph
commit     = 30c4d58db86455128e42ddec96b1ba53c553ba22
```

可确认事实 E1：

1. Resume 不是从暂停的 Python 代码行继续，而是从 Checkpoint 边界重放。
2. 已完成 Task 的持久化结果会被取回，不需要重新计算。
3. 已经开始但没有完成的 Task 在 Resume 时可能再次执行。
4. 官方要求写操作使用幂等键或先查询已有结果。
5. `interrupt` 恢复时会从所属 Node 开头重新运行，因此中断前副作用必须幂等、后移或拆成
   独立 Node/Task。

可确认事实 E2：

1. `Durability` 源码定义了 `sync`、`async` 和 `exit` 三种提交模式：
   - `sync`：下一 Step 开始前同步持久化。
   - `async`：下一 Step 执行时异步持久化。
   - `exit`：只在 Graph 退出时持久化。
2. `CheckpointTask` 区分 Pending、Error 和 Has result，并包含稳定 Task ID。
3. PostgreSQL Checkpointer 的 `put_writes` 将中间写入按
   `thread_id + checkpoint_id + task_id + task_path` 关联保存。
4. `checkpoint_writes` 表的主键是
   `thread_id + checkpoint_ns + checkpoint_id + task_id + idx`。
5. Pregel Runner 的 `commit(task, exception)` 在 Task 取消、中断、失败或成功结束时调用
   `put_writes(task.id, ...)`；成功 Task 没有输出时也会写入 `NO_WRITES` 标记。

源码位置：

- `libs/checkpoint-postgres/langgraph/checkpoint/postgres/base.py`
- `libs/langgraph/langgraph/pregel/_runner.py`
- `libs/langgraph/langgraph/types.py`

这表明 LangGraph 的恢复单位不仅是完整 Graph Snapshot，还包含按稳定 Task ID 保存的执行
结果或错误。它不是一个任意外部工具的执行前账本：如果 Task Closure 已经产生副作用但还没
进入 `commit`，仍然只能依赖幂等操作。

与 KoawaAgent 的差异：

```text
LangGraph
Task 开始/完成身份 + Task Result + Checkpoint Writes

KoawaAgent 当前
只有完整 AgentStep 写入 Snapshot
```

KoawaAgent 目前只有“Task 已完整提交”这一种可恢复证据，没有持久化的“工具已开始但结果
未知”状态。

### 4.2 Temporal

官方资料：

- Activity Definition：
  <https://docs.temporal.io/activity-definition>

本地源码快照：

```text
repository = temporalio/sdk-java
commit     = f68c9bc714c93b3ff8c4c7135e58089811ecfaec
```

可确认事实 E1：

1. 已完成 Activity 不会因为 Workflow Replay 被重新执行。
2. Activity 成功返回或报错后，结果才会写入 Event History。
3. 如果 Worker 已经完成 Activity，但在通知 Temporal Service 前崩溃，Event History
   不会记录完成，Activity 会被重试。
4. Temporal 区分“完成结果只被观察一次”和“Activity 函数可能执行多次”。后者甚至可能
   局部成功多次。
5. Temporal 建议写 Activity 保持幂等，并建议使用由 Workflow Run ID 与 Activity ID
   组成的稳定幂等键。
6. 幂等键最终要由被调用的外部服务执行去重，不是 Temporal Activity 本身自动完成。
7. 一个 Activity 包含多个副作用步骤时，后一步失败会导致整个 Activity 重跑；官方建议
   在粒度与 Event History 成本之间权衡。

可确认事实 E2：

1. Java SDK 的 Activity Worker 同时读取稳定 `activityId` 和递增 `attempt`，并将它们
   放入执行上下文和日志上下文。
2. Activity/Local Activity 状态机和测试都把 `activityId` 作为一次逻辑 Activity 的稳定
   身份，同时单独记录 Attempt。
3. SDK 因而明确区分：
   - logical activity identity；
   - physical execution attempt。

源码位置：

- `temporal-sdk/src/main/java/io/temporal/internal/worker/ActivityWorker.java`
- `temporal-sdk/src/main/java/io/temporal/internal/statemachines/ActivityStateMachine.java`
- `temporal-sdk/src/test/java/io/temporal/internal/statemachines/ActivityStateMachineTest.java`

这正是 KoawaAgent 当前缺少的两个维度。`taskId + nextStep` 只能近似定位逻辑 Step，无法
表示同一 ToolCall 的第几次实际执行。

Temporal 官方描述的边界与 R003-1 完全同构：

```text
Temporal：
Activity 成功
  → Worker 上报前崩溃
  → Event History 没有完成记录
  → Activity 重试

KoawaAgent：
Executor 成功
  → Step Checkpoint 前失败
  → Snapshot 没有 Step
  → Resume 重跑
```

这说明当前失败不是 KoawaAgent 特有 Bug，而是外部副作用与 Durable Log 不能组成原子
提交时的通用分布式系统边界。

### 4.3 Restate

官方资料：

- Architecture：
  <https://docs.restate.dev/references/architecture>
- HTTP invocation/idempotency：
  <https://docs.restate.dev/services/invocation/http>
- Side effects 设计说明：
  <https://www.restate.dev/blog/why-we-built-restate>

本地源码快照：

```text
repository = restatedev/sdk-java
commit     = d533e91d4871e8621fc29c592bafbbcb400d2eaa
```

可确认事实 E1：

1. Restate 使用 log-first Runtime。一个 `ctx.run` 的结果写入复制日志并获得 quorum
   确认后，才定义为 Durable Step 已发生。
2. 已提交 Step 在重试时从 Journal 恢复，不重新执行。
3. 新执行尝试携带递增 epoch，旧 Attempt 的迟到事件会被 fencing，避免两个执行者同时
   推进内部 Journal。
4. 相同 Idempotency-Key 的重复请求会映射到同一个 Invocation，并复用已提交结果。
5. Restate 仍明确说明：任意 Side Effect 如果在执行完成或结果持久化前失败，可能执行
   多次。

可确认事实 E2：

1. `ContextImpl.runAsync` 把用户 Closure 交给 `HandlerContext.submitRun`。
2. `HandlerContextImpl.submitRun` 先调用 `stateMachine.run(name)`：
   - `replayed = true` 时不保留 Closure，因此不会再次执行用户 Action；
   - `replayed = false` 时才把 Closure 加入 `scheduledRuns`。
3. 用户 Action 返回后，SDK 先序列化结果，再调用 `runCompleter.proposeSuccess`。
4. `RunInterceptor` 也明确只拦截真实执行的 Closure，Replay 的 Run 会被 Runner 跳过。

源码位置：

- `sdk-api/src/main/java/dev/restate/sdk/ContextImpl.java`
- `sdk-core/src/main/java/dev/restate/sdk/core/HandlerContextImpl.java`
- `sdk-api/src/main/java/dev/restate/sdk/interceptor/RunInterceptor.java`
- `sdk-core/src/main/java/dev/restate/sdk/core/legacy/ReplayingState.java`

源码调用顺序为：

```text
stateMachine.run(name)
  → 非 Replay 时执行 action.get()
  → serialize(result)
  → proposeSuccess(result)
```

因此 Restate 可以在 Journal 已有 Run Result 时跳过 Closure；但 `action.get()` 已成功、
`proposeSuccess` 尚未形成 Durable Result 时，仍需要幂等或其他外部一致性机制。

重要边界：

```text
Restate 可以保证
已写入自身 Durable Log 的结果不重放

Restate 不能无条件保证
任意外部系统副作用与 Durable Log 原子提交
```

Restate 的 Invocation ID、Idempotency Key 和 epoch fencing 分别对应 KoawaAgent 后续
需要研究的：

- stable toolCallId；
- argumentsHash/幂等键；
- Resume Claim/Lease 与旧执行者隔离。

### 4.4 三者共同结论

三种 Runtime 的具体实现不同，但都把可靠性拆成两层：

```text
层一：Durable Runtime
保存已完成结果、恢复控制流、避免已提交步骤重跑

层二：Side-effect Safety
幂等键、结果查询、去重、补偿或未知结果人工处理
```

它们都没有证明“只要有 Checkpoint，就能让任意外部工具 Exactly Once”。

### 4.5 尚未纳入事实的内容

- 没有根据 Codex 或 Claude Code 的产品表现反推其内部 Tool Ledger 实现。
- 没有宣称 Restate/Temporal 内部机制适合直接移植到 KoawaAgent。
- 没有验证这些框架在 Patch、Git commit 或 MCP 写操作上的具体恢复策略。
- 没有比较实际性能、写放大和存储成本。

### 4.6 本地源码位置

本次浅克隆没有进入 KoawaAgent 仓库，位于系统临时目录：

```text
C:\Users\qaz14\AppData\Local\Temp\koawa-r003-sources-20260726
```

临时目录只用于研究取证，不作为项目构建依赖。研究记录使用 commit hash 固定证据版本，
避免后续 `main` 分支变化导致结论无法复查。

## 5. 候选方案

以下内容当前只是候选，不是 ADR 结论。

### 方案 A：只保存完整 Step

- 核心机制：工具返回后保存 Action、Observation 和 nextStep。
- 优点：结构简单，已提交 Step 可以跳过。
- 缺点：工具成功和 Step 保存之间存在崩溃窗口。
- 失败模式：恢复后重复执行外部副作用。

### 方案 B：工具提供稳定幂等键

- 核心机制：同一逻辑 ToolCall 在重试时携带相同幂等键。
- 优点：工具端可以复用第一次成功结果。
- 缺点：不是所有本地工具、Shell 或 MCP 服务都支持。
- 失败模式：工具忽略幂等键时仍然重复执行。

### 方案 C：Write-ahead Tool Execution Ledger

- 核心机制：执行工具前写入 PREPARED/RUNNING，执行后写入 SUCCEEDED/FAILED。
- 优点：恢复时可以识别同一 ToolCall 及其已知结果。
- 缺点：执行成功、成功结果落账前仍然可能留下未知窗口。
- 失败模式：恢复时看到 RUNNING，但无法确认外部系统是否已成功。

### 方案 D：`OUTCOME_UNKNOWN` 与人工决策

- 核心机制：无法自动确认结果时不盲目重放，标记未知并等待检查或批准。
- 优点：适合 Git commit、付款、外部写入等高风险副作用。
- 缺点：降低自动恢复率，需要人工介入。
- 失败模式：没有配套查询或补偿机制时任务会长期停留。

### 方案 E：Ledger + 外部结果查询

- 核心机制：恢复时使用稳定 ToolCall 身份查询文件、进程、Git 或远端系统，确认第一次
  执行是否已经成功。
- 优点：查询结果可信时，不需要重复执行写操作，也可以自动完成恢复。
- 缺点：不是所有工具都提供可靠查询；查询条件本身必须稳定且能唯一关联逻辑调用。
- 失败模式：查询超时、结果不唯一或外部状态已被其他操作改变时，仍会进入未知结果。

这些候选不是互斥的。当前证据支持把 Step Checkpoint、Ledger、幂等键、结果查询和人工
处理组合使用，而不是选一个机制替代其他机制。

## 6. 假设

- H1：只要在完整 Step 后保存，Resume 就不会重复工具副作用。
- H2：Checkpoint 异常直接中断 Runner，可以消除副作用重复。
- H3：持久化 Snapshot 无法表达“工具可能成功，但结果尚未保存”。
- H4：同一个自动重放策略对不同副作用类别会产生不同的外部结果。
- H5：幂等写可以避免重复改变逻辑状态，但不能避免重复物理执行。
- H6：只要增加 Write-ahead Tool Ledger，恢复时自动重试就不会重复副作用。
- H7：Ledger 配合可靠结果查询可以避免重复执行；无法查询时，停止自动推进可以用可用性
  换取副作用安全。

R003-1、R003-3 与 R003-4 的实验结果：

- H1：拒绝。
- H2：拒绝。直接中断可以防止继续执行，但不能撤销已经发生的调用。
- H3：暂时接受，仍需通过后续方案实验验证如何补足表达能力。
- H4：接受。在相同崩溃点和恢复流程下，三类操作的外部状态变化次数分别为 0、1、2。
- H5：接受。幂等写执行了两次，但相同幂等键只产生一条外部记录。
- H6：拒绝。Ledger 中保留 `RUNNING` 后如果仍然盲目重试，非幂等写依旧执行两次并产生
  两次外部效果。
- H7：接受。在本地受控实验中，结果查询将执行次数保持为 1 并自动完成；人工处理将执行
  次数保持为 1，但任务不再自动完成。

## 7. 最小实验

实验代码：

`src/test/java/com/koawa/agent/agent/research/CheckpointSideEffectCrashExperimentTest.java`

### 输入

- 初始 Snapshot：revision 0、nextStep 0、steps 为空。
- Planner：固定返回 `CALL_MCP_TOOL`。
- Executor：每次调用都把外部副作用计数器加一。
- Checkpoint Lifecycle：第一次 Step 完成时强制抛出
  `AgentCheckpointLifecycleException`。

### 控制变量

- 使用固定 Clock。
- 使用同一个 taskId。
- 使用同一个持久化 Store。
- 恢复状态只从 `AgentCheckpointService.load` 获得。
- 不修改 AgentLoop。

### 操作

```text
创建 revision 0
  → 第一次 Runner 执行工具，counter = 1
  → Step 保存失败
  → 从 Store 加载 revision 0
  → 第二次 Runner 从 nextStep 0 开始
  → 工具再次执行，counter = 2
```

### 指标

- 持久化 revision。
- 恢复后的 nextStep。
- 恢复后的 Step 数量。
- Executor 实际调用次数。

## 8. 故障注入

本次注入点：

```text
Executor 已返回成功 Observation
  → checkpointLifecycle.stepCommitted 抛出异常
```

尚未注入：

- 工具调用过程中进程退出。
- 数据库已提交但客户端收到超时。
- Patch 写入一半。
- Shell 子进程仍在运行。
- Git commit 已成功但结果未保存。
- 外部 MCP 请求超时但远端实际成功。

## 9. 实验结果

执行命令：

```text
mvn -q -Dtest=CheckpointSideEffectCrashExperimentTest test
```

真实结果：

```text
Tests run: 1
Failures: 0
Errors: 0
Skipped: 0
```

关键观测：

```text
第一次工具执行后：
sideEffectCount = 1
storedRevision = 0
storedNextStep = 0
storedSteps = 0

从 Snapshot 恢复并再次运行后：
sideEffectCount = 2
runtimeNextStep = 1
runtimeSteps = 1
```

证据等级：E3。

结论不是“实验测试通过所以系统安全”，而是：

> 实验成功复现了当前系统会重复副作用的失败模式。

### R003-3：三类工具的同故障点对照

实验代码：

`src/test/java/com/koawa/agent/agent/research/ToolSideEffectClassCrashExperimentTest.java`

执行命令：

```text
mvn -q -Dtest=ToolSideEffectClassCrashExperimentTest test
```

真实结果：

```text
Tests run: 3
Failures: 0
Errors: 0
Skipped: 0
```

三组实验复用同一个崩溃/恢复夹具：

```text
revision 0
  → 工具返回成功
  → Step Checkpoint 抛出异常
  → 从 revision 0 / nextStep 0 恢复
  → 同一个逻辑操作再次执行
```

只改变工具操作的副作用类别，结果如下：

| 类别 | 物理执行次数 | 外部状态变化 | 观测结果 |
|---|---:|---:|---|
| 只读 | 2 | 0 | 外部值保持不变，恢复后的 Step 保存第二次读取结果 |
| 幂等写 | 2 | 1 | 两次使用同一幂等键，外部只有一条逻辑记录 |
| 非幂等写 | 2 | 2 | 两次执行分别追加一条记录，产生重复副作用 |

证据等级：E4（受控故障注入和同变量对照）。

该 E4 只支撑当前内存实验中的分类结论，不代表真实文件系统、Git、Shell 或远端 MCP 已经
验证。实验还明确了两个容易混淆的指标：

```text
execution attempts != externally applied effects
```

幂等写没有把执行次数从 2 变成 1；它只让外部逻辑结果保持为 1。重复请求的延迟、费用、
限流和审计噪声依然存在。

### R003-4：恢复方案对照

实验代码：

`src/test/java/com/koawa/agent/agent/research/ToolRecoveryStrategyExperimentTest.java`

固定故障点：

```text
外部写入已经成功
  → Agent Step 尚未保存
  → Ledger 的 SUCCEEDED 尚未写入
  → 模拟进程崩溃
  → 执行恢复策略
```

执行命令：

```text
mvn -q -Dtest=ToolRecoveryStrategyExperimentTest test
```

真实结果：

```text
Tests run: 5
Failures: 0
Errors: 0
Skipped: 0
```

对照指标：

| 恢复方案 | 物理执行 | 外部效果 | Ledger 写入 | 外部查询 | 自动完成 |
|---|---:|---:|---:|---:|---|
| Step-only + 盲目重试 | 2 | 2 | 0 | 0 | 是，但产生重复写 |
| 稳定幂等键 + 重试 | 2 | 1 | 0 | 0 | 是 |
| Ledger + 盲目重试 | 2 | 2 | 3 | 0 | 是，但产生重复写 |
| Ledger + 查询后复用 | 1 | 1 | 3 | 1 | 是 |
| Ledger + 人工处理 | 1 | 1 | 3 | 0 | 否，进入 `OUTCOME_UNKNOWN` |

`Ledger 写入` 只统计实验中的 `PREPARED → RUNNING → 终态`，不包含 Agent Step
Checkpoint 和外部系统自身的写入，因此这里只能说明相对写放大，不能作为真实性能数据。

关键对照结论：

1. Ledger 不会自动把任意外部写入变成 Exactly Once。
2. Ledger 的直接价值是持久化逻辑调用身份、参数摘要和不确定状态，使恢复策略有证据可用。
3. 盲目重试 `RUNNING` 会让“有 Ledger”和“没有 Ledger”产生相同的重复写。
4. 外部查询可用且结果唯一时，能够同时保留副作用安全和自动恢复。
5. 无法查询且操作非幂等时，`OUTCOME_UNKNOWN` 主动牺牲自动完成率，避免再次写入。

证据等级：E4（固定故障点、五方案对照和量化指标）。实验系统仍是内存模型，没有测量
PostgreSQL 写延迟、真实网络超时或具体工具行为。

### 建议恢复矩阵

以下矩阵是进入 ADR 的推荐输入，不是已经批准的生产协议：

| Ledger 状态/能力 | 建议动作 | 关键不变量 |
|---|---|---|
| `SUCCEEDED` | 复用已保存结果 | 不重新调用外部工具 |
| `PREPARED` | 重新取得执行权后执行 | 只有 `RUNNING` 持久化成功后才允许调用工具 |
| `RUNNING` + 只读 | 在预算内重试 | 接受结果可能变化和重复成本 |
| `RUNNING` + 稳定幂等键 | 使用同一个 key 重试 | Attempt 不能生成新的幂等 key |
| `RUNNING` + 可靠结果查询 | 先查询，确认成功则补记 `SUCCEEDED` | 查询必须唯一关联逻辑 ToolCall |
| `RUNNING` + 非幂等且不可查询 | 进入 `OUTCOME_UNKNOWN` | 不允许 Runtime 自动盲目重放 |
| `OUTCOME_UNKNOWN` | 等待人工确认、补偿或批准重试 | 参数变化必须产生新的 ToolCall |

`sideEffectClass` 与恢复能力不应混为一个字段。比如 Git commit 可能不是天然幂等写，但
可以根据 parent、tree、message 等条件查询；外部 MCP 写入可能支持幂等键，也可能完全
不支持。因此 ADR 需要分别表达：

```text
副作用类别
是否支持稳定幂等键
是否支持结果查询
未知结果风险等级
```

### 初步工具映射

| 工具场景 | 初步分类 | 恢复方向 |
|---|---|---|
| 文件读取、搜索 | 只读 | 在成本预算内重试；接受数据可能变化 |
| Patch/文件写入 | 可查询写 | 比较写前/写后内容哈希，再决定成功、重试或冲突 |
| Shell build/test | 依命令而定 | 不按工具名统一分类；检查进程/产物，副作用未知时暂停 |
| Git commit | 可查询的高风险写 | 按 parent/tree/message 查询，不能确认时暂停 |
| 外部 MCP 写入 | 由服务能力声明 | 优先同 key 重试或查询，否则 `OUTCOME_UNKNOWN` |

## 10. 当前结论

当前可以确认：

1. Step Checkpoint 可以保护已经持久化的完整 Step。
2. 它不能覆盖 Executor 成功与 Step 保存成功之间的时间窗口。
3. Checkpoint fail-fast 是必要的，但不足以提供副作用 Exactly Once。
4. 在没有 Ledger、幂等键或结果查询能力时，自动重放未提交工具调用不安全。
5. 成熟 Durable Runtime 也通过“已提交结果复用 + 未完成任务可能重试”描述这一边界。
6. stable toolCallId 解决调用身份问题，但必须配合外部去重或结果查询才能防止副作用重复。
7. Claim/epoch fencing 解决并发执行者问题，但不能单独解决单个执行者在外部调用后的崩溃
   窗口。
8. 统一“自动重放所有未提交工具”的策略不成立；恢复决策至少要区分只读、可验证幂等写
   和非幂等写。
9. 只读重放不产生写副作用，但仍可能重复成本、触发限流，或在外部数据变化时得到不同
   结果，因此“只读”等于可自动重试仍需附加稳定性和成本条件。
10. 幂等写能否安全重放，取决于同一逻辑 ToolCall 是否复用稳定幂等键，以及外部执行方
    是否真正按该键去重。
11. 非幂等写在结果未落账时不能盲目自动重放；恢复时应优先查询结果、补偿或进入
    `OUTCOME_UNKNOWN`。
12. Ledger 是可恢复决策的证据层，不是外部副作用的原子事务层；Ledger 后仍然盲目重试
    不会提高安全性。
13. 推荐方案不是单一机制，而是稳定 ToolCall 身份、参数哈希、Write-ahead Ledger、
    工具恢复能力和未知结果人工路径的组合。
14. `PREPARED` 可安全执行的前提是：Runtime 必须等待 `RUNNING` 持久化成功后才调用
    外部工具。否则 `PREPARED` 本身也可能代表已经执行。
15. 恢复策略同时优化安全性与可用性：查询后复用兼顾两者，人工处理保安全但牺牲自动
    完成率，盲目重试保推进但可能破坏外部状态。

尚不能确认：

- ToolCall ID 应由 Provider、Runtime 还是二者组合生成。
- `PREPARED` 与 `RUNNING` 是否需要两次独立数据库写入，还是可安全合并。
- 真实 Patch、Shell、Git 和远端 MCP 的结果查询条件是否可靠。
- Ledger 的 PostgreSQL 写延迟、事务边界、清理和索引成本。
- 人工处理 `OUTCOME_UNKNOWN` 时需要哪些 API 和审计事件。

## 11. ADR 建议

研究证据与三次实验预算已经收敛，已起草：

```text
docs/adr/ADR-002-tool-side-effect-recovery.md
状态：Accepted（2026-07-29）
```

ADR 至少需要决定：

- SideEffectClass。
- Tool Ledger 写前状态。
- Stable toolCallId 与 argumentsHash。
- 自动重试条件。
- `OUTCOME_UNKNOWN` 的进入和退出规则。
- 高风险操作的人工恢复路径。
- `sideEffectClass` 与幂等/查询能力是否拆分表达。
- `PREPARED → RUNNING` 的持久化与执行顺序不变量。

## 12. 后续研究步骤

1. 回到 Execution Plan 顺序，从 M0-S1 开始实施 Resume 状态矩阵。
2. 继续完成 M0 Resume 闭环。
3. Tool Ledger 仍在 M5-S4 实现，不提前跨越里程碑。
