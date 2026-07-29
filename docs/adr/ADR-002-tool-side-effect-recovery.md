# ADR-002：工具副作用恢复与未知结果策略

## 状态

Accepted（2026-07-29，用户批准进入对应实现）

对应研究：

- `docs/research/R003-checkpoint-tool-side-effects.md`
- E1/E2：LangGraph、Temporal、Restate 官方文档与源码。
- E3/E4：KoawaAgent 崩溃复现、副作用类别对照和五方案恢复对照。

## 背景

KoawaAgent 当前只在完整 `AgentStep` 边界保存 Checkpoint：

```text
execute tool
  → create AgentStep
  → advance currentStep
  → save Checkpoint
```

如果工具已经成功、Checkpoint 尚未保存时进程崩溃，恢复只能看到旧 Snapshot，无法区分：

```text
工具没有执行
工具执行失败
工具已经成功，但结果没有保存
```

R003 实验确认：

- Step-only 恢复会重复非幂等副作用。
- 幂等键能保持一个逻辑结果，但物理调用仍可能发生多次。
- Ledger 后盲目重试仍然会重复副作用。
- 可靠结果查询可以避免重放；无法查询时暂停可以保护外部状态，但降低自动完成率。

当前 `PreparedToolCall` 是进程内对象，只包含 `toolId`、参数和执行器引用。它适合 Policy
判断，不是持久化记录；执行器、客户端或函数引用不得进入 Snapshot 或 Ledger。

本 ADR 决定 Tool Execution Ledger 的领域语义与恢复边界，不在本轮决定具体 DDL、REST
API 或完整 Tool Runtime 实现。

## 决策驱动因素

- 已提交的成功结果必须可复用，不能再次执行外部工具。
- 未知结果不能被伪装成普通失败并盲目重试。
- 逻辑 ToolCall 身份与物理 Attempt 必须分离。
- 审批、参数和恢复必须绑定同一个不可变调用身份。
- Snapshot 继续负责任务恢复；Ledger 负责单次工具调用证据。
- 不承诺任意外部系统的分布式 Exactly Once。
- 没有 Ledger 的 M0 Resume 必须采取保守边界。
- 持久化设计必须支持 PostgreSQL CAS、并发 Resume 和故障注入验证。
- 敏感参数不得以明文写入 Ledger 或日志。

## 候选方案

### 方案 A：继续只保存 Step Checkpoint

- 优点：没有额外表和写入。
- 缺点：无法表达工具开始、Attempt 或未知结果。
- 失败模式：恢复后重复 Patch、Git、Shell 或 MCP 写操作。

### 方案 B：只依赖工具幂等键

- 优点：支持幂等的外部服务可以自然去重。
- 缺点：文件、Shell、Git 和部分 MCP 工具不支持；成功结果仍缺少本地审计证据。
- 失败模式：执行方忽略 key 或每次 Attempt 生成新 key 时仍然重复。

### 方案 C：Ledger 后统一自动重试

- 优点：具有调用身份和执行状态。
- 缺点：把 `RUNNING` 当成失败，仍然无法判断外部操作是否已经成功。
- 失败模式：R003-4 已证明非幂等写仍执行两次、产生两次效果。

### 方案 D：Ledger + 分类恢复 + 幂等/查询/人工处理

- 优点：成功结果可复用；未知结果显式化；可以按工具能力选择恢复方式。
- 缺点：增加持久化写入、状态机、查询接口和人工处理路径。
- 失败模式：工具能力声明错误或查询结果不可靠时，仍可能做出错误恢复决策。

### 方案 E：引入 Temporal/Restate 等外部 Durable Runtime

- 优点：提供成熟的执行历史、重试和持久化机制。
- 缺点：依赖和运维成本显著；仍不能自动消除任意外部副作用的未知窗口。
- 失败模式：把相同的幂等与查询问题转移到 Activity/Run 内部。

## 决策

选择方案 D：保留 Step Checkpoint，并在 M5-S4 引入独立的 Write-ahead Tool Execution
Ledger。Ledger 与幂等键、结果查询、Policy 和人工恢复组合使用。

本 ADR 已获人工批准。后续实现仍必须遵守里程碑顺序和独立切片边界，不能借此提前实现
M5-S4 的生产 Ledger。

### 1. 职责边界

```text
Agent Snapshot
  负责：任务状态、已提交 Steps、nextStep、Pending Interrupt

Tool Execution Ledger
  负责：逻辑 ToolCall、物理 Attempt、执行状态、结果或未知结果

Trace/Event
  负责：面向审计和观测的事件序列
```

Ledger 不保存 Java Executor、Spring Bean、MCP Client、线程或进程对象。

### 2. 调用身份

每个逻辑调用必须具有 Runtime 持久化主键 `toolCallId`：

- 由 Runtime 在写入 `PREPARED` 前生成。
- 同一个逻辑调用的所有 Attempt 保持不变。
- Provider 原生 call ID 单独保存为 `providerToolCallId`，不能替代 Runtime 主键。
- 当前文本 JSON v1 没有 Provider call ID，也不影响 Runtime 建立稳定身份。

每个调用还必须保存：

```text
taskId
toolCallId
providerToolCallId（可空）
toolId
canonicalArguments 或可恢复的参数引用
argumentsHash
sideEffectClass
idempotencyKeyMode
resultLookupMode
state
attempt
result 或 resultRef
typedFailure
createdAt / updatedAt
version（CAS）
```

`argumentsHash` 必须基于带版本的规范化表示计算，不能使用 `Map.toString()`。参数变化必须
产生新的 `toolCallId`；不得借用旧审批或旧 Ledger 记录执行新参数。

Secret 必须保存为受控引用，不得把明文凭据写入 Ledger。无法安全恢复参数的 ToolCall
不得承诺自动 Resume。

### 3. 副作用与恢复能力分离

第一版语义至少区分：

```text
SideEffectClass
  READ_ONLY
  IDEMPOTENT_WRITE
  NON_IDEMPOTENT_WRITE

IdempotencyKeyMode
  NONE
  TOOL_CALL_ID

ResultLookupMode
  NONE
  AUTHORITATIVE
```

`sideEffectClass` 描述操作性质；幂等键和结果查询描述恢复能力。Git commit、Patch 和 MCP
写入不能仅凭工具名称推断恢复方式，能力必须由确定性 Tool Definition/Adapter 声明，模型
不得自行降低风险等级。

### 4. Ledger 状态

```text
PREPARED
WAITING_FOR_APPROVAL
RUNNING
SUCCEEDED
FAILED
OUTCOME_UNKNOWN
```

含义：

| 状态 | 含义 |
|---|---|
| `PREPARED` | 调用身份、参数哈希和能力已经固定，工具尚未获准执行 |
| `WAITING_FOR_APPROVAL` | 等待与原 `toolCallId + argumentsHash` 绑定的人工决定 |
| `RUNNING` | 执行权已经取得，执行前记录已持久化，工具可能已经产生副作用 |
| `SUCCEEDED` | 结果已经持久化，可直接复用 |
| `FAILED` | 已确认失败；是否可重试由 typed failure 和 Policy 决定 |
| `OUTCOME_UNKNOWN` | 无法确认副作用是否发生，禁止自动盲目重放 |

`FAILED` 只用于结果确定的失败。网络超时、连接中断或客户端未知响应不能因为抛出异常就
自动标为 `FAILED`；对可能写入的操作，这些情况默认进入查询或 `OUTCOME_UNKNOWN`。

### 5. 执行顺序不变量

```text
validate tool and canonicalize arguments
  → create PREPARED
  → evaluate Policy
  → WAITING_FOR_APPROVAL（如需要）
  → acquire task/tool execution ownership
  → commit RUNNING
  → execute external tool
  → commit SUCCEEDED / FAILED / OUTCOME_UNKNOWN
  → project durable result into AgentStep
  → save Step Checkpoint
```

强制要求：

1. `RUNNING` 持久化成功前禁止调用外部工具。
2. `SUCCEEDED` 持久化成功前禁止把 Agent Step 视为已提交。
3. Step Checkpoint 失败但 Ledger 已 `SUCCEEDED` 时，Resume 必须复用 Ledger 结果生成
   Step，不得再次调用工具。
4. Ledger 终态写入失败时保留 `RUNNING` 语义，按恢复矩阵处理。
5. 任何旁路工具调用都属于 P1 缺陷。

### 6. 状态转换

```text
PREPARED ───────────────→ RUNNING
    │                        │
    └→ WAITING_FOR_APPROVAL ─┘
                             │
                             ├→ SUCCEEDED
                             ├→ FAILED
                             └→ OUTCOME_UNKNOWN
```

补充规则：

- `SUCCEEDED` 不得重新进入 `RUNNING`。
- 可重试的 `FAILED` 使用同一个 `toolCallId`、递增 Attempt，并重新取得执行权。
- `OUTCOME_UNKNOWN` 只能经显式结果确认、补偿或人工批准转移。
- 人工批准重试必须保留原参数哈希；参数变化创建新 ToolCall。
- 每次 Attempt 和人工决策必须可审计，不能只覆盖最后一次原因。

### 7. Resume 恢复矩阵

恢复优先顺序为：复用已知结果 → 权威查询 → 同 key 重试 → 只读预算重试 → 人工处理。

| 当前证据 | 恢复动作 |
|---|---|
| `SUCCEEDED` | 复用结果，不执行工具 |
| `PREPARED` | 重新取得执行权后继续；依赖“未到 RUNNING 不执行”不变量 |
| `WAITING_FOR_APPROVAL` | 继续等待原审批，不重新向模型规划参数 |
| `RUNNING` + `AUTHORITATIVE` 查询 | 先查询；成功则补记 `SUCCEEDED`，明确未执行再按 Policy 处理 |
| `RUNNING` + `IDEMPOTENT_WRITE` | 使用原 `toolCallId` 作为同一幂等键重试 |
| `RUNNING` + `READ_ONLY` | 在超时、费用和次数预算内重试 |
| `RUNNING` + 非幂等且不可查询 | 写入 `OUTCOME_UNKNOWN`，停止自动推进 |
| `FAILED` + 明确可重试 | 递增 Attempt，经 Policy 后重试 |
| `OUTCOME_UNKNOWN` | 等待人工确认、补偿或明确批准 |

“查询未找到”不自动等价于“工具没执行”。只有 Adapter 能权威区分“成功”“明确不存在”
和“仍不确定”时，才允许从查询结果自动恢复。

### 8. 并发与执行权

- Ledger 状态更新使用 CAS/version。
- 同一 `toolCallId` 同一时刻只能有一个有效 Attempt。
- 只有持有任务执行权和 ToolCall 执行权的 Worker 才能写 `RUNNING` 或终态。
- M0-S4 的 Claim/Lease 必须与 Ledger Attempt 关联并具有 fencing 语义。
- Fencing 防止旧 Worker 更新内部状态，但不能撤销旧 Worker 已经发出的外部请求；仍需
  幂等键、查询或 `OUTCOME_UNKNOWN`。

### 9. 审批和安全边界

审批必须绑定：

```text
taskId
toolCallId
argumentsHash
policyVersion
requestedCapability
```

批准只授权该不可变调用。重试是否复用批准由 Policy 明确决定；权限、参数、工作区或风险
等级变化时必须重新审批。

外部 Tool Result、查询结果和 MCP 返回都属于不可信数据，只能作为恢复证据，不能扩大
权限或修改 Runtime Policy。

### 10. M0 的过渡约束

生产 Ledger 位于 M5-S4，不提前塞入 M0。

在 Ledger 尚未实现前：

- M0 可以恢复已持久化 Steps、修复终态、消费澄清 Interrupt 和实现执行权 Claim。
- M0 不得宣称能安全自动恢复“可能已经执行但 Step 未保存”的副作用工具。
- 对来源不明的 `RUNNING` Snapshot，M0-S1 状态矩阵必须保留保守拒绝或显式人工决策路径。
- 不得为了让 Resume Demo 自动完成而默认重放缺失的高风险写操作。

## 后果

### 正面影响

- 已保存结果可以复用，避免明确成功的 ToolCall 重跑。
- 未知结果具有独立语义，不再伪装成普通失败。
- Provider call ID、Runtime ToolCall ID 和 Attempt 职责清晰。
- 审批、Policy、幂等、查询与恢复使用同一个参数身份。
- Snapshot 继续保持任务级简洁边界。

### 负面影响

- 每次工具调用增加执行前、执行后以及可能的审批状态写入。
- 需要独立持久化、CAS、清理、索引和结果大小策略。
- 自动完成率会因 `OUTCOME_UNKNOWN` 降低。
- Tool Adapter 必须声明恢复能力并实现可信查询，开发成本增加。
- 人工处理和补偿 API 需要在后续里程碑落地。

### 兼容与迁移影响

- 不修改现有 `agent_checkpoint` V1 migration。
- 第一阶段使用新 Flyway migration 增加独立 Ledger 表，不把 Ledger 塞入 Snapshot JSON。
- 旧 Snapshot 仍可读取；没有 Ledger 记录的旧任务按 M0 保守规则处理。
- 当前 `PreparedToolCall` 保持进程内类型；持久化领域对象不得包含其 `executor` 字段。
- 公共 API 在 M8-S1b 前不强制变化；内部必须先保留未知结果语义，不能丢失。
- `OUTCOME_UNKNOWN` 如何映射到外层 TaskStatus 需要与 R002/M5-S3 一起确定，本 ADR 只要求
  停止自动执行且可查询，禁止暂时映射成可自动重试的普通 `FAILED`。

## 验证计划

### 领域与组件测试

- `toolCallId` 在多个 Attempt 间稳定。
- 参数规范化和 `argumentsHash` 确定性。
- 参数变化不能复用旧审批或结果。
- 非法状态转换被拒绝。
- `SUCCEEDED` 结果复用不调用 Executor。
- `RUNNING` 按只读、幂等、查询、不可查询四类恢复。
- `OUTCOME_UNKNOWN` 不自动执行。

### 故障注入

分别在以下边界终止：

```text
PREPARED 前
PREPARED 后
RUNNING 提交前
RUNNING 提交后、工具调用前
工具成功后、SUCCEEDED 前
SUCCEEDED 后、Step Checkpoint 前
Step Checkpoint 后
```

验证调用次数、外部效果数、Ledger 状态、Attempt 和 Snapshot revision。

### PostgreSQL/Testcontainers

- Ledger CAS 和非法并发转换。
- 两个 Resume 只有一个 Attempt 获得执行权。
- 事务提交成功但客户端收到超时。
- JSON/参数哈希、时间精度和 Flyway migration。
- 真实 PostgreSQL 通过前不得宣称 Ledger 并发语义已验证。

### 工具专项补验

- Patch：写前/写后哈希和并发修改。
- Shell：进程仍存活、已退出但结果未落账、命令隐藏副作用。
- Git：按 parent/tree/message 查询结果。
- MCP：幂等 key、请求超时但远端成功、查询不可用。

## 建议实施切片

ADR 批准后仍按 Execution Plan 顺序推进：

1. `M0-S1`：Resume 状态矩阵加入“无 Ledger 时不盲目重放未知副作用”的约束。
2. `M0-S2` 至 `M0-S5`：完成当前 Resume 闭环，不实现 Ledger。
3. `M1-S1`：`ToolCallItem` 保留 Provider call ID，并允许 Runtime 建立自身身份。
4. `M5-S4a`：Ledger 领域协议、状态转换和恢复决策，不接数据库。
5. `M5-S4b`：PostgreSQL schema、Store、CAS 与 Testcontainers。
6. `M5-S4c`：Runtime 执行顺序与 `SUCCEEDED` 结果复用。
7. `M5-S4d`：幂等、结果查询、`OUTCOME_UNKNOWN` 和故障注入。
8. `M8-S1b`：未知结果查询、人工确认、补偿/重试 API。

每个子切片仍受 8 个生产文件上限约束；实际文件清单在实施前重新确认。

## 尚未决定

- Ledger 采用单表状态行、状态行加 Attempt 表，还是 append-only event 表。
- `PREPARED` 与 `RUNNING` 是否可以安全合并为一次执行前提交。
- `OUTCOME_UNKNOWN` 对应新的 TaskStatus，还是独立 Pending Interrupt。
- Result 内联大小、Artifact Store 边界和保留期限。
- 具体工具的权威查询协议。

这些未知项不改变本 ADR 的核心安全不变量，在对应实施切片前必须单独确认，不能由实现
便利性静默决定。

## 回滚方案

- Ledger 通过独立 migration 新增，不修改或删除 V1 Checkpoint 表。
- Runtime 集成使用可关闭的执行路径；回滚时停止创建新 Ledger 记录，但保留历史只读。
- 已存在 `RUNNING/OUTCOME_UNKNOWN` 记录不得删除或自动标记失败，必须保留人工处理入口。
- 不执行破坏性 down migration；若放弃方案，新增 migration 标记停用并保留审计数据。
- 回滚到 Step-only 时必须恢复 M0 保守限制，不能重新启用未知副作用的自动重放。
