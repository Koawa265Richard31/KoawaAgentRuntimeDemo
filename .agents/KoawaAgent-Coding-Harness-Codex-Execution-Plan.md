# KoawaAgent Coding Agent Harness 严格实施与 Codex 执行控制规划

> 项目目录：`D:\KoawaAgent`
>
> 当前技术栈：Java 17、Spring Boot 3.5、PostgreSQL、JDBC/Flyway、MCP Java SDK
>
> 文档版本：v1.2
>
> 编制日期：2026-07-25（v1.2 修订：2026-08-01，见 `.agents/CHANGELOG.md`）
>
> 文档用途：作为编码代理后续编码的唯一阶段路线、切片边界、验收标准和停止条件
>
> 适用对象：任何编码代理（Codex、Claude Code 等）。文中"Codex"泛指编码代理。
>
> 本文档取代已废止的 `docs/KoawaAgent-Runtime-Plan.md`。

---

## 0. 使用方式

本文档不是一次性交给 Codex “全部实现”的需求书，而是用于严格控制多轮编码。

每次只允许执行一个编号切片，例如：

```text
执行切片 M1-S1：定义 ModelTurn v2 领域协议。
严格遵守《KoawaAgent Coding Agent Harness 严格实施与 Codex 执行控制规划》。
本轮不得开始 M1-S2，不得顺手修改 Provider Adapter。
```

每个切片都必须经历：

```text
读取约束
  → 检查工作区
  → 给出本切片计划
  → 实现最小改动
  → 运行目标测试
  → 运行全量回归
  → 更新开发记录
  → 汇报变更和风险
  → 停止
```

Codex 不得因为“后续实现很自然”“顺手可以完成”或“减少重复修改”跨越切片。

---

## 1. 项目目标与架构决策

### 1.1 产品目标

KoawaAgent 最终是一套可独立运行、可嵌入应用的 Java coding-agent harness：

1. 基础使用能力逐步对齐 OpenCode：
   - 多模型 Provider。
   - 持久化 Session。
   - 文件读取、搜索、Patch 和写入。
   - Shell、持久进程和增量输出。
   - Git diff/status。
   - LSP/编译诊断。
   - MCP。
   - 项目规则和自定义命令。
2. 工程深度学习 Codex 和 Claude Code：
   - Provider 原生工具调用。
   - 工作区边界。
   - 沙箱与审批。
   - 上下文压缩。
   - 长任务恢复。
   - 计划、验证和失败纠正。
   - Skills、Hooks 和隔离 Subagent。
3. 保持 KoawaAgent 自身特色：
   - Java/Spring 生态。
   - 明确的 Runtime/Persistence 反腐层。
   - Checkpoint revision/CAS。
   - 可审计工具执行账本。
   - 不依赖 Python Agent 框架。

### 1.2 不采用通用 Agent 框架替换核心

底层选型确定为：

| 层级 | 选型 |
|---|---|
| Coding harness | KoawaAgent 自研 Java Runtime |
| 内层推理 | 动态 Agent loop |
| 外层生命周期 | 显式任务状态机 |
| 本地工具 | Harness 原生 Tool Runtime |
| 扩展工具 | MCP |
| 持久化 | PostgreSQL Snapshot + Tool Execution Ledger |
| 模型调用 | Provider-native Adapter |
| 长期耐久编排 | 后期可选 Temporal |
| 多 Agent | 后期、隔离工作区、按需引入 |

LangGraph、OpenAI Agents SDK、AutoGen、Flowise 只作为语义与功能参考，不作为 KoawaAgent 核心依赖。

### 1.3 核心控制结构

```text
Task Lifecycle State Machine
│
├── RUNNING
├── WAITING_FOR_INPUT
├── WAITING_FOR_APPROVAL
├── COMPLETED
├── FAILED
├── CANCELLED
└── TIMED_OUT
        │
        ▼
Coding Agent Loop
│
├── Assemble Context
├── Invoke Provider
├── Receive ModelTurn
├── Validate Tool Calls
├── Policy / Approval
├── Execute Tools
├── Commit Run Items
├── Compact Context
└── Continue / Finish
```

任务创建即写入 revision 0 且状态为 RUNNING，不存在持久化的 CREATED 状态（与 `AgentTaskStatus` 实现一致，2026-07-26 裁决）。

外层状态机不能交给模型决定。模型可以提出动作，但任务生命周期、权限、恢复和最终验证由 Runtime 控制。

---

## 2. 当前基线

### 2.1 已有能力

截至 2026-07-25，项目已经具备：

- `AgentState`、`AgentStep`、`AgentAction`、`AgentObservation`。
- `AgentLoopRunner`。
- `AgentPlanner` 与 `LlmAgentPlanner`。
- `RoutingAgentActionExecutor`。
- MCP Tool Registry 和调用器。
- 工具 allowlist 策略。
- 超时、取消、有限规划恢复。
- `AgentTaskSnapshot`。
- Snapshot Mapper/Codec。
- In-memory/JDBC Checkpoint Store。
- PostgreSQL/Flyway。
- revision/CAS。
- 初始、Step 和终态 Checkpoint 生命周期。
- `WAITING_FOR_INPUT` 和 Pending Interrupt 的基础结构。
- 当前全量测试基线至少为 96 个测试（2026-07-26 统计）。

验证边界（2026-07-26 修订）：revision/CAS 与 JDBC Store 长期仅经 H2 内嵌库验证；
真实 PostgreSQL 语义由 `PostgresJdbcAgentCheckpointStoreTest`（Testcontainers，
2026-07-26 引入）覆盖 CAS、并发写入与时间精度。完整的重启恢复 E2E 仍属于 M0-S5。
在两者都通过前，不得将某能力描述为"PostgreSQL 已验证"。

### 2.2 当前最大技术限制

当前 Planner 使用以下方式：

```text
工具描述写进 Prompt
  → 模型返回文本 JSON
  → AgentActionParser
  → 单个 AgentAction
```

这会限制：

- Provider 原生 tool call。
- 一个模型回合返回多个工具调用。
- 流式 tool arguments。
- reasoning/content block。
- provider response continuation。
- 工具调用 ID 与模型响应 ID 的可靠关联。
- 未来并行只读工具。

因此，完成当前 Resume 闭环后，必须优先升级模型回合协议，而不是立即堆积更多工具。

### 2.3 兼容要求

在 M1 完成前：

- 现有 `AgentAction` 路径继续可用。
- 当前 REST API 不得无故破坏。
- 当前 Snapshot 必须能读取。
- 旧 Snapshot 必须有明确迁移或兼容策略。
- 每个迁移步骤都必须通过全量回归。

---

## 3. Codex 全局执行协议

以下规则适用于所有切片，优先级高于切片中的便利性建议。

### 3.1 开始编码前必须执行

Codex 必须：

1. 读取：
   - `AGENTS.md`
   - `README.md`
   - 本文档
   - `docs/development/KoawaAgent-Development-Notes.md` 最近相关章节
2. 检查：
   - `git status --short`
   - 当前分支。
   - 最近 5 个提交。
   - 与本切片相关的生产代码和测试。
3. 明确汇报：
   - 本切片目标。
   - 预计修改文件。
   - 明确不修改的内容。
   - 验收命令。
4. 如果发现未提交用户改动与本切片重叠，停止并说明冲突，不覆盖。

### 3.2 修改范围规则

Codex 只能：

- 修改当前切片“允许修改”列出的模块。
- 为满足编译而做最小相邻修改。
- 增加与本切片直接对应的测试和文档。

Codex 不得：

- 顺手重命名无关类型。
- 全项目格式化。
- 修改无关 import 顺序。
- 升级依赖，除非切片明确要求。
- 引入 Lombok 之外的新代码生成器。
- 改写历史迁移文件。
- 删除现有测试以通过构建。
- 降低断言强度。
- 将失败测试标记为 disabled。
- 在未批准的情况下改变公共 API。
- 将密钥、Token、用户目录或本机绝对路径写入仓库。

### 3.3 单切片大小约束

默认上限：

- 生产代码修改不超过 8 个文件。
- 新增/修改测试不超过 8 个文件。
- 数据库迁移不超过 1 个。
- 一个切片只解决一个主问题。

超过上限时，Codex 必须先拆分子切片并停止，不能自行扩大范围。

### 3.4 测试顺序

每个切片至少执行：

1. 最窄目标测试。
2. 相关包测试。
3. `mvn test` 全量回归。

涉及 PostgreSQL 语义时，必须增加真实 PostgreSQL/Testcontainers 验证；H2 只能作为快速组件测试，不能代替 PostgreSQL 验收。

### 3.5 完成汇报格式

Codex 每个切片结束必须提供：

```text
切片：
结果：
修改文件：
关键设计：
目标测试：
全量回归：
未完成事项：
风险：
建议下一切片：
```

如果有失败，必须给出真实错误，不得用“应该可以”“理论上通过”代替测试结果。

### 3.6 停止条件

出现以下任一情况必须停止：

- 要求改变本阶段架构决策。
- 需要跨越当前切片。
- 需要删除或覆盖用户修改。
- 公共协议存在两个以上同等合理方案，且会显著影响后续。
- 数据迁移可能破坏已有 Snapshot。
- 需要扩大文件系统、网络或命令权限。
- 测试暴露出当前切片之外的既有缺陷。
- 无法验证真实 Provider 或操作系统语义。
- 全量回归失败且失败不由本切片直接造成。

---

## 4. 代码与架构守则

### 4.1 分层

推荐包边界：

```text
agent/
├── runtime/        Agent loop、run lifecycle
├── model/          Provider-neutral model protocol
├── provider/       OpenAI/Anthropic/compatible adapters
├── tool/           Tool definition、registry、execution
├── workspace/      Workspace/session/file boundary
├── terminal/       Process、PTY、streaming output
├── policy/         Capability policy、approval
├── checkpoint/     Snapshot、mapper、store、resume
├── context/        Context assembly、budget、compaction
├── verification/   Test/build/diagnostic gates
├── event/          Run item、trace、metrics
└── evaluation/     Scenario、runner、report
```

不要求一次性搬迁现有包。只有当切片明确负责某个模块时才允许渐进迁移。

### 4.2 领域模型与持久化模型分离

必须继续保持：

```text
Mutable Runtime State
  ↕ explicit mapper
Immutable Versioned Snapshot
```

禁止直接序列化包含运行时 Bean、客户端、线程、进程或函数引用的对象。

### 4.3 Provider-neutral 不等于最低公分母

统一模型必须保留 Provider 扩展信息：

```java
ModelTurn
├── provider
├── model
├── responseId
├── outputItems
├── usage
├── finishReason
└── providerMetadata
```

禁止将 OpenAI、Anthropic 的原生工具调用先转换为文本 JSON 再重新解析。

### 4.4 工具执行只能经过 Runtime

所有本地、MCP 和未来 Subagent 工具调用必须经过：

```text
ToolCall
  → Schema Validation
  → Workspace Resolution
  → Capability Classification
  → Policy Decision
  → Approval if needed
  → Tool Ledger
  → Execution
  → Result Projection
  → Checkpoint
```

Provider Adapter、Controller 和 Prompt 代码不得直接调用工具。

### 4.5 副作用安全

任何可能产生副作用的调用都必须具有：

- 稳定 `toolCallId`。
- `argumentsHash`。
- `sideEffectClass`。
- 执行前记录。
- 执行后结果记录。
- 恢复决策。

系统不承诺分布式 Exactly Once，但必须做到：

- 重复可检测。
- 成功结果可复用。
- 未知结果不盲目重放。
- 高风险未知结果等待人工处理。

### 4.6 不可信数据边界

以下内容均视为不可信：

- 仓库文件。
- 工具输出。
- Shell 输出。
- 网页内容。
- MCP 返回。
- Git commit message。
- Issue/PR 文本。

不可信内容不能覆盖 System/Project Policy，不能自动扩大权限。

---

## 5. 里程碑总览

| 里程碑 | 目标 | 完成标志 |
|---|---|---|
| M0 | 完成当前 Runtime 恢复闭环 | 重启、澄清、并发 Resume 可验证 |
| M1 | 模型事件协议 v2 | 原生 tool call、多调用、流式事件可表达 |
| M2 | Workspace Kernel | 所有文件动作受工作区边界控制 |
| M3 | Coding Tools | 搜索、读取、Patch、Git、诊断可用 |
| M4 | Terminal Runtime | 命令、持久进程、增量输出、取消可用 |
| M5 | Policy/Sandbox | 能力级审批、路径与命令隔离可用 |
| M6 | Context Engine | 规则发现、预算、压缩和 Artifact 化可用 |
| M7 | Verification Loop | 模型完成声明必须经过确定性验收 |
| M8 | Product Surface | Session API、事件流和 CLI/TUI 基础可用 |
| M9 | Skills/Hooks | 可复用技能与确定性生命周期 Hook |
| M10 | Subagents | 只读/Worktree 隔离子 Agent |
| M11 | Evaluation | 固定基准、回放、指标和回归门禁 |

严格顺序：

```text
M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8 → M9 → M10 → M11
```

允许 M11 的最小评测基础设施在 M1 后提前建立，但不得提前实现 M10 多 Agent。

---

## 6. M0：完成当前 Checkpoint 与 Resume 闭环

### M0 目标

在改变 `AgentAction`/Provider 协议前，先把已有持久化设计闭合，形成可靠基线。

### M0-S1：定义 Resume 用例与状态迁移矩阵

允许修改：

- `agent/checkpoint`
- 新增 resume command/result 类型
- 对应单元测试
- 开发记录

交付：

- `AgentResumeCommand`
- `AgentResumeResult`
- 可恢复状态矩阵
- terminal 状态拒绝规则
- expected revision 校验

必须覆盖：

| 当前状态 | Resume |
|---|---|
| RUNNING | 仅在取得执行权后允许 |
| WAITING_FOR_INPUT | 需要匹配 interrupt |
| WAITING_FOR_APPROVAL | 留待 M0-S3 或明确拒绝 |
| COMPLETED | 拒绝 |
| FAILED | 默认拒绝 |
| CANCELLED | 拒绝 |
| TIMED_OUT | 默认拒绝 |

禁止：

- 实现 HTTP Controller。
- 修改 Agent loop。
- 实现工具审批。

验收：

- 状态矩阵单元测试。
- revision 冲突测试。
- 全量回归。

### M0-S2：实现 Snapshot 恢复与终态修复

交付：

- 加载 Snapshot。
- Mapper 恢复新 `AgentState`。
- 最后 Step 已为 terminal、但任务仍 RUNNING 时补写终态。
- 已提交 Step 不重新执行。

禁止：

- 引入工具账本。
- 修改模型协议。

验收：

- revision N 恢复后从 `nextStep` 继续。
- terminal Step 修复只增加终态 revision。
- 原工具 Handler 不被再次调用。

### M0-S3：实现澄清 Interrupt 消费

交付：

- 验证 `taskId + interruptId + expectedRevision`。
- 一次性消费 USER_INPUT interrupt。
- 用户回复进入恢复上下文。
- 重复提交明确返回幂等结果或 409。

验收：

- 同一 taskId 继续。
- 原 Steps 不丢失。
- 重启后仍能恢复。
- 错误 interruptId 不推进状态。

### M0-S4：执行权租约或 CAS Claim

交付：

- 同一任务只能被一个执行者推进。
- Claim/lease 或等效 CAS 设计。
- 进程崩溃后的过期策略。

禁止：

- 为此引入 Redis。
- 实现分布式调度平台。

验收：

- 两个并发 Resume 只有一个成功。
- 失败请求得到明确冲突。

### M0-S5：Resume REST API 与 PostgreSQL E2E

交付：

- Task 查询。
- Resume API。
- 真实 PostgreSQL 重启恢复测试。
- API 错误映射。
- 会话历史持久化：替换 `InMemoryAgentConversationStore`，重启后跨任务对话历史
  可恢复（2026-07-26 补充；在此之前 M0 的"重启恢复"仅覆盖单任务 Snapshot）。

过渡裁决（2026-08-01）：

- M0 先按当前运行时的真实语义保存类型化完整 Turn：一个 USER 输入和一个
  `FINAL_ANSWER`/`ASK_CLARIFICATION` 可交付输出。
- Turn 必须保留稳定来源身份，至少能用 `taskId + terminalStepIndex` 幂等覆盖首次 Chat、
  ASK、Resume、FINAL 和 terminal-step recovery。
- M0 不臆测 M1 的 `ModelContextItem`、`OutputItem` 或 `RunItem` 物理表结构；当前 Turn-row 是
  可交付 Conversation 投影，不是完整运行 Trace。
- 该过渡方案不得被解释为跳过 M1 全链路协议升级；M3 入口仍受 §9 的硬门禁约束。

里程碑出口：

- 所有 M0 测试通过。
- 文档记录真实 revision 序列。
- 现有 API 无破坏。

---

## 7. M1：模型回合与工具调用协议 v2

### M1 目标

从“文本 JSON 单 Action”升级为 Provider 原生、多 OutputItem 的模型回合协议。

### M1-S1：定义 v2 领域协议

新增但不接入 Runtime：

```text
ModelRequest
ModelTurn
OutputItem
AssistantMessageItem
ToolCallItem
ReasoningSummaryItem
ModelUsage
ModelFinishReason
```

要求：

- 不依赖 OpenAI/Anthropic SDK 类型。
- 所有集合不可变或防御性复制。
- Tool call 有稳定 call ID。
- 支持一个回合多个 ToolCall。
- 支持 provider metadata。
- 结构可以显式版本化。

禁止：

- 修改 `AgentLoopRunner`。
- 删除 `AgentAction`。
- 实现任何 Provider。

### M1-S2：定义 RunItem/Event 协议

区分：

```text
Provider OutputItem
ModelContextItem
Runtime RunItem
Conversation Turn projection
Persistent TraceEvent
```

职责必须明确：

- `OutputItem` 是 Provider 返回的原生语义项。
- `ModelContextItem` 是下一次模型请求需要看到的规范上下文，至少能表达 user/assistant message、
  tool-call echo 和 tool-result；不能退化为一个拼接字符串。
- `RunItem` 是单 Task 的权威运行时间线，除消息外还包含 policy、approval、tool execution、
  verification 等 Runtime 事实。
- `Conversation Turn` 是跨 Task 的用户可见投影，只保存用户输入和可交付 ASSISTANT 输出；不得把
  RunItem 全量回灌 Prompt，也不得从 Trace 临时猜 Conversation。
- `TraceEvent` 是 RunItem 的持久化/查询表示，不等于模型上下文。

RunItem 至少支持：

- Model turn started/completed。
- Message produced。
- Tool call requested。
- Policy decided。
- Approval requested。
- Tool execution started/completed。
- Context compacted。
- Verification started/completed。
- Run completed。
- Model text delta。
- Tool arguments delta。
- Model turn failed。

要求：

- 每个事件带 taskId、runId、sequence、timestamp。
- sequence 由 Runtime 生成，不由模型提供。
- 流式 delta、排序和聚合必须是类型化协议；M1 不提前实现 SSE/WebSocket、断线续传或 CLI 展示。

### M1-S3：OpenAI Responses Provider Adapter

交付：

- 请求转换。
- 原生 function tool schema。
- 文本、tool call、usage、response ID 映射。
- 错误分类。
- 流式响应的 typed delta 映射可先实现最小版本，但聚合、回放和 Gate 统一在 M1-S7c 收口。

要求：

- Adapter 测试使用录制/固定响应，不依赖真实网络。
- API key 不写入测试和日志。
- 保留 response continuation 信息。

### M1-S4：Anthropic Messages Provider Adapter

要求与 M1-S3 相同，并保留 Anthropic content block 和 tool use ID。

### M1-S5：v2 Agent loop

默认拆分执行（2026-07-26 修订，避免超出 8 文件限额）：

- M1-S5a：v2 loop 骨架 + 单 ToolCall 串行。
- M1-S5b：多 ToolCall 串行 + v1 兼容层。

新建 v2 loop 或通过清晰兼容层接入，禁止一次性删除 v1：

```text
invoke model
  → validate ModelTurn
  → collect tool calls
  → policy
  → execute
  → append tool results
  → invoke model again
```

第一版多 ToolCall 策略：

- 默认按返回顺序串行。
- 只有全部标记为只读且无依赖时才允许未来并行。
- 本切片不实现并行。

### M1-S6：v1 迁移与 Snapshot schema v2

默认拆分执行（2026-07-26 修订）：

- M1-S6a：schema v2 读写 + 旧版本兼容读取（含版本分派重构
  `AgentTaskSnapshotJsonCodec`，当前实现对非当前版本直接拒绝）。
- M1-S6b：v1 路径弃用 + 功能等价测试。

交付：

- Snapshot schema 版本升级。
- 旧 Snapshot 兼容读取或明确迁移。
- v1 路径弃用说明。
- 删除 v1 之前必须有功能等价测试。

### M1-S7：全链路协议适配与 M3 入口 Gate

默认拆分执行：

- M1-S7a：默认 Runtime 切换到 `ModelTurn v2 + ModelContextItem + RunItem`，旧
  `AgentActionParser` 只保留隔离兼容入口。
- M1-S7b1：Snapshot v2 保存重建当前 Task model context 所需的 items/refs，并适配 Resume 与
  terminal-step recovery。
- M1-S7b2：canonical deliverable message、terminal committer 与 Conversation Store 全路径适配；
  M0 Turn-row 通过明确投影兼容跨 Task 历史。
- M1-S7c：固定流式 Provider fixture、typed delta 聚合、重放和最终 Gate 审计。

完整适配必须覆盖：

- 所有已注册 Provider Adapter 输出 canonical `ModelTurn`，保留 responseId、toolCallId、usage、
  finish reason 和 provider metadata。
- 默认 loop 实际消费 `ModelTurn`，多 ToolCall 的 callId、顺序和 ToolResult 关联在正常执行与恢复
  后一致。
- Runtime 实际产生有序 RunItem；v2 Snapshot 能恢复当前 Task 的 message/tool context，v1
  Snapshot 可兼容读取或明确迁移。
- 首次 Chat、ASK、Resume、FINAL、terminal-step recovery 都从 canonical deliverable message
  生成同一 Conversation 投影，无遗漏、重复或双真源。
- 下一 Task 能把 M0 Turn-row 投影回 canonical `ModelContextItem`；`AgentRunResult` 只保留为 API
  结果投影，不得成为持久化协议。
- 固定流式响应能聚合为与非流式等价的最终 `ModelTurn`。产品级 SSE/WebSocket 仍属于 M8。

回退规则：

- 协议尚未完整适配时，禁止半切换、dual writer 或猜测性 Message/Item-row migration；继续使用
  ADR-004 的类型化 Turn-row 作为唯一跨 Task Conversation 真源。
- Conversation 的物理 Turn-row 可以在完整 adapter 下继续存在；“协议已升级”以 canonical v2
  主路径和全链路行为为准，不以是否换成 item-row 表为准。
- 回退只适用于 Conversation 的持久化形状，不豁免核心 `ModelTurn/ToolCall` v2 门禁。M1-S7 未
  通过时不得进入 M3，不能一边保留 v1 工具动作主路径一边实现第二套 Coding Tools。

里程碑出口：

- Runtime 不再依赖模型返回手写 JSON Action。
- OpenAI/Anthropic 至少一个真实 smoke test 可人工运行。
- 单回合多个工具调用可被完整记录。
- `ModelContextItem`、RunItem、Snapshot/Resume 和 Conversation projection 已按 M1-S7 全路径接入，
  旧 `ChatMessage`/`AgentAction` 只存在于明确兼容层。
- PostgreSQL/Testcontainers 证明 Conversation terminal transaction、幂等冲突、重启恢复和并发
  顺序；因 Docker 跳过不得通过出口。
- 固定流式 fixture 的 typed delta 可确定性聚合和回放。
- 建立 3–5 个固定评测场景与回放脚本（M11 最小前置，2026-07-26 补充）。
  此后任何 Prompt、协议、Context policy 或循环算法变更都必须先回放这些场景。

---

## 8. M2：Workspace Kernel

### M2-S1：Workspace 模型与路径解析

新增：

```text
WorkspaceId
WorkspaceDescriptor
WorkspaceSession
WorkspacePathResolver
WorkspaceAccessViolation
```

要求：

- rootPath 必须规范化为绝对路径。
- 所有目标路径解析后仍位于允许根目录。
- 禁止 `..`、符号链接或 junction 逃逸。
- Windows 与 Unix 语义分别测试。
- 不把本机路径永久写入共享配置。

### M2-S2：Workspace 生命周期

支持：

- 创建/加载 Workspace Session。
- Task 绑定 Workspace。
- Task 结束后 Workspace 是否保留的明确策略。
- Workspace metadata 持久化。

禁止：

- 此时创建 Git worktree。
- 容器沙箱。

### M2-S3：Workspace Snapshot

记录但不复制全部文件：

- 根目录。
- Git branch/HEAD。
- dirty 状态。
- 允许写目录。
- 环境摘要。
- 项目规则摘要。

里程碑出口：

- 后续所有本地工具必须依赖 `WorkspaceSession`。
- 工具不得自行接受任意绝对路径绕过 Resolver。

---

## 9. M3：Coding Tools

### M3 入口硬门禁（2026-08-01）

开始 `M3-S1` 前必须重新审计并确认 M1 里程碑出口全部通过，尤其包括：Provider Adapter、默认
v2 Runtime、多 ToolCall 关联、RunItem、Snapshot/Resume、Conversation projection 和 typed
stream aggregation。任一项未完成：

- 不得开始 M3-S1。
- 不得以 ADR-004 的 M0 Turn-row 过渡实现作为核心协议豁免。
- 保持当前可交付语义和唯一数据真源，返回 M1-S7 完成缺失适配，禁止把协议债带入 Coding Tools。

该门禁早于“在 M3 完成前补协议”的最晚要求，目的是避免搜索、Patch、Git、Diagnostics 建在旧
`AgentAction` 主路径上后整体返工。物理 Conversation item-row、Run Items 查询 API、SSE/WebSocket
和断线续传不属于本门禁，分别由独立 ADR/后续 M8 切片决定。

### 工具统一协议

所有工具实现：

```java
ToolDefinition definition();
ToolCapability capability();
ToolResult execute(ToolExecutionContext context, JsonObject arguments);
```

`ToolResult` 必须区分：

- `success`
- `content`
- `structuredContent`
- `artifactReferences`
- `truncated`
- `errorType`
- `metadata`

### M3-S1：只读文件工具

工具：

- `list_files`
- `read_file`
- `grep`
- `find_files`

要求：

- 分页/offset/limit。
- 二进制文件检测。
- 大结果截断。
- 忽略规则。
- 结果包含稳定路径。

验收：

- 不能读取 workspace 外。
- 大文件不会完整注入模型。
- 错误路径是结构化失败。

### M3-S2：Patch 工具

优先实现 `apply_patch`，晚于 Patch 再实现直接 write。

要求：

- 读取版本摘要/文件哈希。
- 修改前校验旧上下文。
- 全部目标通过校验后再应用。
- 失败不能留下半 Patch。
- 保留换行和编码策略。
- 生成 diff artifact。

### M3-S3：受控文件创建与删除

执行顺序调整（2026-07-26 修订）：本切片的删除能力依赖审批暂停/恢复机制，
必须在 M5-S3 完成后执行；在此之前 delete 类工具不注册，文件创建部分可先行。

删除规则：

- 默认 REQUIRE_APPROVAL。
- 只能删除明确文件。
- 目录递归删除不在本里程碑支持。
- 删除前记录目标和恢复信息。

### M3-S4：Git 只读工具

工具：

- `git_status`
- `git_diff`
- `git_log`
- `git_show`

禁止：

- commit、push、reset、checkout、clean。

### M3-S5：Diagnostics 接口

先定义抽象：

```text
DiagnosticProvider
Diagnostic
DiagnosticSeverity
DiagnosticRange
```

第一实现可以来自：

- Maven compiler/test parser，或
- 一个语言的 LSP。

不要同时实现多语言 LSP 管理平台。

里程碑出口：

- Agent 可以安全完成“搜索 → 阅读 → Patch → 查看 diff → 诊断”闭环。
- 至少一个固定 E2E 能展示 `ModelTurn → 多 ToolCall → Coding Tools → RunItem → Final` 的关联，
  并保留 taskId、runId、toolCallId、sequence 和验证证据，作为面试交付材料。

---

## 10. M4：Terminal Runtime

### M4-S1：一次性命令执行

要求：

- argv 与 shell command 明确区分。
- cwd 必须通过 Workspace Resolver。
- timeout。
- stdout/stderr 分离。
- 输出上限。
- exit code。
- 环境变量 allowlist。
- 取消。

禁止：

- 默认继承全部父进程环境。
- 自动暴露密钥。
- 将用户输入拼接为未经转义的命令。

### M4-S2：命令风险分类

分类至少包括：

```text
READ_ONLY
BUILD
TEST
NETWORK
PACKAGE_INSTALL
WRITE_SYSTEM
DESTRUCTIVE
UNKNOWN
```

分类器使用确定性规则。模型可以提供说明，但不能自己决定风险等级。

### M4-S3：持久进程 Session

支持：

- `process_start`
- `process_poll`
- `process_write`
- `process_stop`

要求：

- processId。
- 增量游标。
- ring buffer 或 artifact。
- Task/Workspace ownership。
- 应用退出后的明确语义。

### M4-S4：PTY

根据 Java/OS 可行性单独评审依赖。

Codex 必须先提交 ADR，比较：

- 纯 Java Process。
- PTY4J 或类似库。
- 外部 helper。
- Windows ConPTY。

ADR 批准前不得引入 PTY 依赖。

里程碑出口：

- Agent 可以启动测试或开发服务、读取增量输出并停止。

---

## 11. M5：Policy、Approval 与 Sandbox

### M5-S1：Capability 模型

定义：

```text
READ_WORKSPACE
WRITE_WORKSPACE
DELETE_WORKSPACE
RUN_READ_COMMAND
RUN_BUILD
RUN_TEST
RUN_NETWORK
INSTALL_PACKAGE
ACCESS_SECRET
WRITE_OUTSIDE_WORKSPACE
DESTRUCTIVE_GIT
EXTERNAL_SIDE_EFFECT
```

### M5-S2：Policy Decision v2

结果：

```text
ALLOW
DENY
REQUIRE_APPROVAL
ALLOW_WITH_RESTRICTIONS
```

Restrictions 可包含：

- allowed roots。
- command prefix。
- network mode。
- duration。
- tool call count。

### M5-S3：审批暂停/恢复

审批必须绑定：

- taskId。
- interruptId。
- toolCallId。
- 原始参数哈希。
- policy version。
- requested capability。

批准后参数不能由模型替换。参数发生变化必须产生新的审批。

### M5-S4：Tool Execution Ledger

状态：

```text
PREPARED
WAITING_FOR_APPROVAL
RUNNING
SUCCEEDED
FAILED
OUTCOME_UNKNOWN
```

执行前必须写 `PREPARED/RUNNING`。恢复策略按 side-effect class 区分。

### M5-S5：文件系统沙箱

先实现 Harness 级边界，再评估 OS/container 沙箱。

要求：

- Java 路径校验不能被宣称为完整安全沙箱。
- 文档明确威胁模型。
- 外部命令仍可能通过 shell 访问系统，必须由进程沙箱进一步限制。

### M5-S6：OS/container Sandbox ADR 与原型

比较：

- Docker/Podman。
- Windows Sandbox/Job Object。
- WSL。
- 本机受限账户。

只选一个平台完成参考实现，其他平台保持 SPI。

里程碑出口：

- 写文件、网络、安装依赖和危险命令有明确审批路径。
- 审批和沙箱是两个独立控制面。

---

## 12. M6：Context Engine

### M6-S1：Context Source 与优先级

来源：

1. System policy。
2. 用户授权。
3. 项目规则。
4. 当前任务。
5. 当前计划。
6. 最近 Run Items。
7. 相关文件。
8. 历史摘要。
9. 工具结果。

低优先级内容不能覆盖高优先级规则。

### M6-S2：项目规则发现

支持候选：

- `AGENTS.md`
- `CLAUDE.md`
- 项目内 KoawaAgent 配置

要求：

- 从 workspace root 到目标文件目录按层级发现。
- 记录来源路径。
- 限制总大小。
- 规则内容作为项目指令，但仍低于 Runtime Policy。

### M6-S3：Token/字符预算

定义预算：

- System/Rules。
- 用户任务。
- 最近消息。
- 工具输出。
- 文件内容。
- 预留输出。

不得只用“最近 N 条消息”作为上下文控制。

### M6-S4：Tool Output Artifact

大型输出：

```text
完整内容 → Artifact Store
摘要/首尾/关键行 → 模型上下文
引用 → artifactId + offset
```

Agent 可按需继续读取 Artifact，不能一次注入全部日志。

### M6-S5：Compaction

压缩必须保留：

- 原始目标。
- 用户约束。
- 当前计划和进度。
- 未解决错误。
- 当前 diff 摘要。
- 已运行的验证。
- 等待审批。
- 关键文件路径。

压缩前后必须有一致性测试。

里程碑出口：

- 长任务不会因工具输出线性撑爆上下文。
- Resume 后能恢复关键任务语义。

---

## 13. M7：计划与验证闭环

### M7-S1：Task Plan 模型

计划项：

```text
id
description
status
evidence
blockedReason
```

状态由 Runtime 校验：

```text
PENDING
IN_PROGRESS
COMPLETED
BLOCKED
```

同一时刻默认最多一个 `IN_PROGRESS`。

### M7-S2：Verification Profile

项目可定义：

```yaml
verify:
  compile:
    - mvn -q -DskipTests compile
  test:
    - mvn test
  inspect:
    - git diff --check
```

命令仍受 Policy 控制。

### M7-S3：Finalization Gate

模型产生 FinalOutput 后，Runtime 检查：

- 是否有未完成计划项。
- 是否有未审批动作。
- 是否存在未记录的 dirty change。
- 必需验证是否执行。
- 验证是否通过。
- 是否超过用户授权范围。

未通过则向 Agent 返回结构化反馈，不提交 COMPLETED。

### M7-S4：失败纠正循环

要求：

- 验证失败进入有限修复预算。
- 同一错误重复出现触发停止。
- 不允许无限测试—修复循环。
- 预算耗尽返回 FAILED 或 WAITING_FOR_INPUT。

里程碑出口：

- 模型不能仅凭自我声明完成任务。
- 完成状态有可查询证据。

---

## 14. M8：Session、API 与交互面

### M8-S1：Session API

默认拆分执行（2026-07-26 修订）：

- M8-S1a：Session 创建/列出/查询。
- M8-S1b：Run Items 查询、取消、Resume/Approval 接入。

支持：

- 创建 Session。
- 列出 Session。
- 继续 Session。
- 创建 Task。
- 查询 Run Items。
- 取消 Task。
- Resume/Approval。

### M8-S2：流式事件

先定义 SSE/WebSocket 之一。

事件必须：

- 有 sequence。
- 可断线续传。
- 不泄露隐藏 reasoning 或密钥。
- 区分模型文本、工具状态和审批请求。

### M8-S3：CLI

CLI 第一版只承担：

- 提交任务。
- 查看流式状态。
- 审批/拒绝。
- 查看 diff。
- 取消。
- Resume Session。

不要在本阶段构建复杂 TUI。

### M8-S4：TUI ADR

评估 Java TUI 与独立前端进程。批准 ADR 后再开发。

---

## 15. M9：Skills 与 Hooks

### M9-S1：Skill 格式

Skill 只描述：

- 名称。
- 触发条件。
- 指令。
- 可选模板/脚本引用。
- 所需 capability。

Skill 不能自行扩大 Runtime 权限。

### M9-S2：Hooks

Hook 点：

- task created。
- before model。
- after model。
- before tool。
- after tool。
- before compact。
- before final。
- task completed。

Hook 必须：

- 有超时。
- 有失败策略。
- 可审计。
- 不能绕过 Tool Runtime。

### M9-S3：项目命令

支持 Markdown/YAML 定义的可复用命令，但命令执行仍经过 Policy。

---

## 16. M10：Subagents

### M10-S1：只读 Explorer

主 Agent 可以委派只读检索任务。

要求：

- 独立上下文预算。
- 只读能力。
- 结构化结果。
- 不直接修改主 Agent 状态。

### M10-S2：Reviewer

输入为：

- 任务目标。
- diff。
- 测试结果。
- 相关文件。

只返回审查结果，不执行写操作。

### M10-S3：Worktree Worker

要求：

- 独立 Git worktree。
- 独立 Workspace Session。
- 独立 Tool Ledger。
- 主 Agent 显式选择是否集成。

禁止多个写 Agent 共享同一目录。

### M10-S4：合并与冲突

先实现 diff/commit 级集成，不实现自由共享内存。

里程碑出口：

- 多 Agent 不破坏工作区一致性。
- 主 Agent 对最终集成保持控制。

---

## 17. M11：Evaluation Harness

### 17.1 基础指标

- Task completion rate。
- 首次修复成功率。
- 平均模型回合数。
- 平均工具调用数。
- Token/费用。
- 测试通过率。
- 非法路径访问拦截数。
- 审批次数。
- 重复工具调用拦截数。
- Resume 成功率。
- Compaction 后任务保持率。

### 17.2 固定场景

至少覆盖：

1. 单文件 Bug 修复。
2. 跨文件重构。
3. 先调查后不修改。
4. 测试失败后修复。
5. 用户要求与项目规则冲突。
6. Prompt injection 藏在仓库文件中。
7. Patch 上下文过期。
8. 命令超时。
9. 后台进程持续输出。
10. 应用重启后继续。
11. 工具执行结果未知。
12. 审批通过/拒绝。
13. 上下文压缩后继续。
14. Explorer Subagent。
15. Worktree Worker 冲突。

### 17.3 回归门禁

任何 Prompt、模型参数、Context policy、Tool schema 和循环算法变更都必须运行固定场景。

禁止只凭单个 Demo 判断 harness 改进。

---

## 18. 功能对齐矩阵

| 能力 | OpenCode 对齐 | Codex/Claude Code 深度 | KoawaAgent 里程碑 |
|---|---|---|---|
| 多 Provider | 是 | 原生协议保真 | M1 |
| Session | 是 | Resume/Checkpoint | M0、M8 |
| 文件搜索读取 | 是 | 路径边界和分页 | M2、M3 |
| Patch | 是 | 乐观校验、原子应用 | M3 |
| Shell | 是 | 风险分类、沙箱、审批 | M4、M5 |
| 持久进程 | 是 | 增量游标和恢复语义 | M4 |
| Git/LSP | 是 | 结构化验证反馈 | M3、M7 |
| MCP | 已有 | 权限与输出预算 | M5、M6 |
| 项目规则 | 是 | 分层优先级 | M6 |
| Compaction | 基础 | 长任务关键能力 | M6 |
| Plan | 基础 | 状态与证据 | M7 |
| Verification | 基础 | 确定性完成门禁 | M7 |
| Skills/Hooks | 是 | 权限不继承 | M9 |
| Subagents | 是 | 只读/Worktree 隔离 | M10 |
| Evals | 部分 | Harness 进化基础 | M11 |

---

## 19. 数据兼容与迁移规则

### 19.1 Snapshot

每次结构变化必须：

- 增加或检查 `schemaVersion`。
- 提供旧版本读取测试。
- 明确字段默认值。
- 禁止静默忽略无法恢复的字段。
- 在 Mapper 边界抛出专用异常。

### 19.2 数据库

- 已发布 Flyway migration 永不修改。
- 新结构通过新 migration 增加。
- CAS 字段保留为可索引列。
- JSON 与索引列读取时交叉校验。
- 数据删除必须是独立、明确批准的维护操作。

### 19.3 API

公共 API 变更遵循：

1. 新增字段优先。
2. 新旧协议并行。
3. 标记 deprecated。
4. 有迁移文档。
5. 最后删除。

Codex 不得在同一切片中“新增 v2 并删除 v1”。

---

## 20. Git 与提交控制

### 20.1 每个切片一个意图明确的提交

提交信息示例：

```text
Add resumable agent task command model
Add provider-neutral model turn protocol
Add workspace path boundary
Add atomic patch tool
Add tool execution ledger
```

禁止：

- `misc fixes`
- `update`
- `refactor and features`
- 把多个切片塞进一个提交。

### 20.2 提交前检查

```text
git status --short
git diff --check
git diff --stat
mvn test
```

Codex 必须列出仍未提交的用户改动，不得把它们混入提交。

### 20.3 不自动 Push

除非用户明确要求，Codex 只允许本地提交，不允许：

- push。
- 创建 PR。
- 修改远程分支。
- force push。
- rebase 用户提交。

---

## 21. ADR 要求

以下决策必须先写 ADR，再编码：

- PTY 库。
- OS/container sandbox。
- TUI 技术。
- Temporal。
- LSP 客户端库。
- Artifact Store 后端。
- 多 Provider SDK。
- Subagent worktree 合并策略。

ADR 模板：

```markdown
# ADR-NNN：标题

## 状态
Proposed

## 背景

## 决策驱动因素

## 候选方案

## 决策

## 后果

## 验证计划

## 回滚方案
```

Codex 可以在一个切片中调研并起草 ADR，但不得同时默认 ADR 已批准并实施。

---

## 22. Definition of Done

一个切片只有同时满足以下条件才完成：

- 实现范围没有超出切片。
- 生产代码和测试边界清晰。
- 正常、失败和边界路径有测试。
- 目标测试通过。
- `mvn test` 通过。
- 没有 disabled 测试。
- 没有未解释的 warning/error。
- 新数据结构有版本或兼容策略。
- 新工具有 capability 和超时。
- 新外部副作用有执行记录。
- 日志不泄露凭据和敏感数据。
- 开发记录同步更新。
- Codex 明确停止，没有开始下一切片。

一个里程碑只有在：

- 所有子切片完成。
- 里程碑出口测试通过。
- 架构文档更新。
- 至少一个端到端场景通过。
- 已知限制被记录。

后才可以进入下一里程碑。

---

## 23. Codex 每轮可复制控制提示

### 23.1 实施切片提示

```text
你正在修改 D:\KoawaAgent。

严格依据：
1. KoawaAgent Coding Agent Harness 严格实施与 Codex 执行控制规划
2. KoawaAgent-Codex-Operating-Guide.md
3. docs/development/KoawaAgent-Development-Notes.md

本轮只执行：[填写切片编号和名称]

开始前：
- 读取相关规划和代码。
- 检查 git status、当前分支、最近提交。
- 说明本轮目标、预计修改文件、不修改内容和验收命令。
- 发现用户未提交改动与本切片重叠时停止。

执行约束：
- 不跨切片。
- 不升级无关依赖。
- 不全项目格式化。
- 不删除或弱化测试。
- 不改变未授权公共 API。
- 不推送远程。
- 生产代码超过 8 个文件时先停下并拆分。

完成前：
- 运行最窄目标测试。
- 运行相关包测试。
- 运行 mvn test。
- 运行 git diff --check。
- 更新 Development Notes。

最后按固定格式汇报，并在完成本切片后停止，不开始下一切片。
```

### 23.2 只做设计评审提示

```text
本轮只评审 [切片/ADR]，不修改生产代码。

请输出：
- 当前实现证据。
- 至少两个候选方案。
- 兼容性与迁移影响。
- 失败与恢复语义。
- 安全边界。
- 推荐方案及理由。
- 建议拆分切片。

除非我随后明确批准，不得开始编码。
```

### 23.3 代码审查提示

```text
只审查当前工作区与 [基准提交] 的差异，不实施修复。

重点检查：
- 是否跨越批准切片。
- 状态迁移与 Checkpoint 边界。
- 工具副作用与幂等。
- 路径、命令、网络和审批边界。
- Snapshot/API 兼容。
- 并发与 revision CAS。
- 测试是否覆盖失败路径。
- 是否有泄密、越权或 Prompt injection 风险。

按 P0/P1/P2/P3 输出具体文件和行号；没有问题时明确说明没有发现阻断项。
```

### 23.4 失败恢复提示

```text
上一次切片执行失败。本轮不得继续增加功能。

请先：
- 复现失败。
- 区分本切片回归、既有缺陷、环境问题。
- 保留用户改动。
- 给出最小修复方案。
- 只修改与失败直接相关的内容。
- 重新运行原失败测试和全量回归。

失败未关闭前不得开始下一切片。
```

---

## 24. 第一批建议执行序列

从当前项目状态开始，建议依次向 Codex 下达：

```text
1. M0-S1：定义 Resume 用例与状态迁移矩阵
2. M0-S2：实现 Snapshot 恢复与终态修复
3. M0-S3：实现澄清 Interrupt 消费
4. M0-S4：执行权租约或 CAS Claim
5. M0-S5：Resume REST API 与 PostgreSQL E2E
6. M1-S1：定义 ModelTurn v2 领域协议
7. M1-S2：定义 RunItem/Event 协议
```

在 M0 完成前，不应让 Codex 开始：

- Workspace 工具。
- Shell。
- LSP。
- Subagent。
- TUI。
- Provider v2 接入。

在 M1-S1/M1-S2 评审通过前，不应让 Codex 重写现有 Agent loop。

---

## 25. 最终验收愿景

一个完整 coding-agent 任务最终应呈现：

```text
用户提交代码任务
  → 创建 Task + Workspace Session
  → revision 0
  → 加载项目规则和上下文
  → Provider 原生 ModelTurn
  → 只读搜索/读取
  → 形成计划
  → 受控 Patch
  → 运行测试
  → 测试失败并反馈
  → 有限修复
  → Finalization Gate
  → 保存最终 diff、验证证据和 Trace
  → COMPLETED revision
```

遇到高风险命令：

```text
ToolCall
  → Policy = REQUIRE_APPROVAL
  → Prepared Tool Record
  → WAITING_FOR_APPROVAL
  → 应用重启
  → 用户批准原 toolCallId
  → 恢复
  → 执行一次
  → 验证
  → 完成
```

项目的成功标准不是“模型看起来会写代码”，而是：

> 模型在受控工作区中，借助可恢复、可审计、可验证的 harness，长期完成真实代码任务，同时无法绕过 Runtime 扩大权限。
