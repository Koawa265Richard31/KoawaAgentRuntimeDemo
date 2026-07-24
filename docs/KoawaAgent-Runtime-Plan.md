# KoawaAgent Runtime 设计与实施规划

> 仓库：`koawa-hua/KoawaAgentRuntimeDemo`
>
> 项目定位：面向 MCP 工具编排的轻量级、可控、可恢复 Agent Runtime
>
> 文档状态：架构基线与实施规划 v1.0
>
> 更新日期：2026-07-23

## 1. 项目目标

KoawaAgent 的目标不是建设一个全能 Agent 平台，而是通过一套规模受控的 Java Runtime，完整展示 Agent 从规划到执行、从失败到恢复、从暂停到继续的底层机制。

最终需要证明以下能力：

1. 模型只能产出受约束的 Action，不能绕过 Runtime 直接执行工具。
2. Runtime 能够推进和记录每个 Step，并在明确的停止条件下结束。
3. 工具失败、模型输出异常、超时和取消都有确定的状态语义。
4. 应用重启后可以从最近一个安全边界恢复任务。
5. 澄清和危险操作审批可以暂停原任务，并在用户输入后继续。
6. 恢复不会无条件重复执行有副作用的工具。
7. 上下文长度、执行步数和恢复次数都有预算。
8. 固定评测场景能够验证 Runtime 的可靠性，而不只验证 Demo 效果。

## 2. 明确边界

### 2.1 本仓库负责

- Agent State、Step、Action、Observation 和 StopReason。
- Plan–Act 循环及确定性停止条件。
- LLM Planner 与 Action 解析。
- Action Handler 路由。
- MCP 工具发现与调用。
- 工具执行策略、审批和幂等控制。
- Checkpoint、暂停、恢复与取消。
- 上下文预算和历史压缩。
- 结构化事件、执行记录和评测。
- 最小 REST API 与演示场景。

### 2.2 本仓库不负责

- 内置 RAG、向量检索、文档导入和知识库管理。
- 通用多 Agent 编排。
- LangGraph 风格的通用图 DSL。
- 可视化工作流设计器。
- MCP 注册中心。
- A2A、Skills 市场和插件市场。
- 通用代码执行沙箱。
- 完整的企业级可观测平台。
- 面向所有模型厂商的全量 SDK 封装。

需要知识能力时，KoawaAgent 通过 MCP 调用独立的 RAG 服务。

## 3. 设计原则

### 3.1 Runtime 掌握控制权

LLM 负责提出下一步 Action，Runtime 负责：

- 校验 Action。
- 决定是否允许执行。
- 执行并记录 Observation。
- 保存 Checkpoint。
- 判断继续、暂停或终止。

### 3.2 每个外部副作用都有身份

任何可能产生副作用的工具调用必须具有稳定的 `toolCallId`，并在执行前写入持久化记录。恢复时先查询调用记录，再决定复用结果、重试或等待人工确认。

### 3.3 暂停是一种状态，不是异常结束

澄清和审批不再等同于任务结束，而是进入可恢复状态：

```text
RUNNING
  ├─→ WAITING_FOR_INPUT
  ├─→ WAITING_FOR_APPROVAL
  ├─→ COMPLETED
  ├─→ FAILED
  ├─→ CANCELLED
  └─→ TIMED_OUT
```

### 3.4 只在安全边界恢复

第一版只在 Step 边界保存和恢复，不尝试恢复 Java 方法调用栈。恢复后从 Snapshot 指定的 `nextStep` 继续。

### 3.5 接口先于存储实现

Runtime 依赖 `AgentCheckpointStore`、`ToolExecutionStore` 等接口，不直接绑定数据库。测试使用内存实现，演示环境提供一种可跨重启的参考实现。

### 3.6 演示能力必须有自动化验收

每增加一个 Runtime 能力，必须同时增加：

- 单元测试。
- 状态迁移测试。
- 重启或恢复测试。
- 至少一个失败路径测试。

## 4. 成熟框架参考与取舍

### 4.1 LangGraph

采用的思想：

- 使用稳定的 Thread/Task ID 定位一条执行状态。
- 在步骤边界保存 Checkpoint。
- Checkpoint 和跨任务长期记忆使用不同抽象。
- Interrupt 保存状态并等待外部 Resume。
- 恢复时保持执行顺序确定。
- 外部 API 调用需要幂等，避免恢复时重复副作用。

不采用的内容：

- 通用 StateGraph DSL。
- Super-step 并行调度。
- 子图和多 Agent 图编排。
- Time Travel UI。
- 完整的 Channel/Reducer 系统。

KoawaAgent 当前是顺序 Plan–Act Loop，因此一个 Step 就是第一版的安全持久化边界。

### 4.2 Spring AI Alibaba Graph / Agent Framework

采用的思想：

- Checkpointer 与 Runtime 解耦。
- 动态中断和预执行中断是两类不同能力。
- HITL 必须保存线程状态并支持后续更新。
- 工具、模型和上下文策略应有明确扩展点。

不采用的内容：

- Sequential、Parallel、Routing、Loop 等通用多 Agent 工作流。
- Graph 节点和边的通用编排接口。
- 多模态、语音和 A2A。

### 4.3 Spring AI

主要用于校验工具生命周期：

```text
工具定义
→ 模型选择工具
→ Runtime 解析调用
→ 执行策略检查
→ 工具执行
→ 结果写回模型上下文
→ 模型继续或返回答案
```

KoawaAgent 继续保留用户控制的工具执行模式，以便在每次执行前插入审批、幂等、超时和事件记录。

### 4.4 LangChain4j Agentic

只参考其按 Step 保存作用域状态和 Planner 执行位置的恢复思路，不引入多 Agent Scope 或 Agentic 编排 API。

## 5. 当前架构基线

当前主链路：

```text
AgentChatController
  → AgentChatFacade
  → DefaultAgentChatService
  → AgentLoopRunner
      → AgentPlanner
      → AgentActionExecutor
          → AgentActionHandler
              → MCP / Clarification / Final Answer
      → AgentEventSink
```

当前已有能力：

- `AgentState` 保存单次任务状态。
- `AgentStep` 保存 Action 和 Observation。
- `LlmAgentPlanner` 生成结构化 Action。
- `AgentActionParser` 解析并约束模型输出。
- `RoutingAgentActionExecutor` 路由 Handler。
- `CallMcpToolActionHandler` 调用 MCP 工具。
- `AllowListAgentExecutionPolicy` 控制工具白名单。
- `DefaultAgentRecoveryPolicy` 处理规划失败。
- `AgentLoopRunner` 控制步数、超时、取消和事件。
- `InMemoryAgentConversationStore` 保存短期会话历史。
- REST API 提供聊天和取消入口。

当前主要缺口：

- `AgentState` 只在进程内存在。
- `ASK_CLARIFICATION` 仍然是终止，而不是暂停。
- 执行策略只有允许和拒绝。
- 工具调用没有稳定的幂等标识。
- Checkpoint、对话历史和工具执行记录尚未分层。
- 上下文只有固定条数限制，没有 Token/字符预算和摘要。
- Event 主要写日志，不能查询完整执行历史。

## 6. 目标运行时模型

### 6.1 任务状态

新增 `AgentTaskStatus`：

```java
public enum AgentTaskStatus {
    RUNNING,
    WAITING_FOR_INPUT,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
```

`AgentStopReason` 表示一次 Run 为什么返回，`AgentTaskStatus` 表示整个持久化任务当前处于什么状态。两者不能混用。

### 6.2 Snapshot

新增 `AgentTaskSnapshot`，至少包含：

- `schemaVersion`
- `taskId`
- `conversationId`
- `userId`
- `revision`
- `status`
- `originalQuestion`
- `nextStep`
- `maxSteps`
- `deadlineAt`
- `steps`
- `historySnapshot`
- `recoveryContext`
- `pendingInterrupt`
- `createdAt`
- `updatedAt`

Snapshot 只保存可序列化数据，不能保存 Spring Bean、MCP Client、函数或线程对象。
`nextStep` 是恢复后将要执行的步骤索引；已完成步骤数可以由 `steps.size()` 推导，
不重复保存 `currentStep`，避免两个游标产生不一致。

### 6.3 Checkpoint Store

```java
public interface AgentCheckpointStore {
    AgentTaskSnapshot save(
            AgentTaskSnapshot snapshot,
            long expectedRevision
    );

    Optional<AgentTaskSnapshot> load(String taskId);

    List<AgentTaskSnapshot> list(String conversationId);

    void delete(String taskId);
}
```

要求：

- 使用 `revision` 做乐观并发控制。
- 首次保存使用 `expectedRevision = -1` 和 `revision = 0`。
- 更新时数据库当前版本必须等于 `expectedRevision`，新 Snapshot 必须是下一 revision。
- 同一任务不能被两个恢复请求同时推进。
- revision 更新时原子校验任务身份和生命周期迁移。
- 每个 Step 完成后保存。
- 进入等待状态前必须保存。
- 终态 Snapshot 保留，用于审计，不能再次恢复。

实现顺序：

1. `InMemoryAgentCheckpointStore`：单元测试和接口验证。
2. `JdbcAgentCheckpointStore`：参考持久化实现。
3. 数据库中保存索引字段和 JSON Snapshot，避免第一版过度拆表。

### 6.4 Interrupt

新增：

```text
AgentInterrupt
├── interruptId
├── type: CLARIFICATION | TOOL_APPROVAL
├── taskId
├── stepIndex
├── payload
├── createdAt
└── expiresAt
```

Resume 请求：

```text
AgentResumeCommand
├── taskId
├── interruptId
├── expectedRevision
├── responseType
└── payload
```

规则：

- Resume 必须绑定原 `taskId` 和 `interruptId`。
- 已消费的 Interrupt 不能重复消费。
- 终态任务不能 Resume。
- Resume 后生成新 revision，再进入 AgentLoop。

### 6.5 工具执行决策

将当前决策扩展为：

```java
ALLOW
DENY
REQUIRE_APPROVAL
```

`REQUIRE_APPROVAL` 不执行工具，而是：

1. 保存 PreparedToolCall。
2. 创建审批 Interrupt。
3. 保存 WAITING_FOR_APPROVAL Snapshot。
4. 返回审批请求。
5. 批准后绑定原 PreparedToolCall 执行。
6. 拒绝后写入失败 Observation，让 Planner 决定下一步。

### 6.6 工具执行账本

新增 `ToolExecutionRecord`：

- `toolCallId`
- `taskId`
- `stepIndex`
- `toolId`
- `requestHash`
- `arguments`
- `status`
- `result`
- `error`
- `approvalId`
- `startedAt`
- `completedAt`

状态：

```text
PREPARED
→ WAITING_FOR_APPROVAL
→ RUNNING
→ SUCCEEDED | FAILED | OUTCOME_UNKNOWN
```

恢复策略：

- `SUCCEEDED`：直接复用原 Observation。
- `FAILED`：根据失败类型决定是否重试。
- `PREPARED`：尚未执行，可以安全继续。
- `RUNNING` 且进程已重启：标记 `OUTCOME_UNKNOWN`，不得盲目重放副作用工具。
- 工具支持幂等键时，将 `toolCallId` 传给 MCP Tool。

第一版不承诺分布式 Exactly Once，只提供“可检测重复 + 尽量避免重复 + 未知结果人工处理”。

## 7. API 规划

保留：

```http
POST /api/agent/v1/chat
POST /api/agent/v1/tasks/{taskId}/cancel
```

新增：

```http
GET  /api/agent/v1/tasks/{taskId}
GET  /api/agent/v1/tasks/{taskId}/steps
POST /api/agent/v1/tasks/{taskId}/resume
POST /api/agent/v1/tasks/{taskId}/approval
```

统一返回：

- `taskId`
- `conversationId`
- `status`
- `stopReason`
- `revision`
- `content`
- `pendingInterrupt`
- `stepCount`
- `failureType`
- `errorMessage`

并发要求：

- Resume 和 Approval 必须携带 `expectedRevision`。
- revision 不一致返回 `409 Conflict`。
- 重复提交同一 Interrupt 返回幂等结果或明确冲突。

## 8. 上下文工程规划

### 8.1 三种状态分离

- Checkpoint：恢复一条任务所需的完整运行状态。
- Conversation History：同一会话中提供给模型的短期历史。
- Long-term Memory/RAG：跨任务知识，通过外部 Store 或 MCP 获取。

第一版不在 KoawaAgent 内实现 Long-term Memory。

### 8.2 预算

新增 `AgentContextBudget`：

- 最大历史字符数或估算 Token 数。
- 最大 Step 数。
- 最大 Observation 长度。
- 最大单次工具结果长度。
- 最大规划恢复次数。

### 8.3 压缩

处理顺序：

1. 保留当前用户问题。
2. 保留最近 Step。
3. 对旧历史生成摘要。
4. 对大型 Observation 保留关键字段和截断标记。
5. 错误 Observation 必须保留 error type、message 和关键 metadata。

新增 `ConversationSummarizer` 接口，但第一版只提供简单实现，不建设通用记忆框架。

### 8.4 Prompt 版本

每个 Snapshot 和 Trace 记录：

- Planner Prompt 版本。
- Final Answer Prompt 版本。
- 模型名称。
- 可用工具集合摘要。

这样才能重现和比较同一评测场景。

## 9. 可观测性与评测

### 9.1 结构化记录

保留 `AgentEventSink`，新增可查询实现：

- Run Started/Completed。
- Step Started/Completed。
- Action Planned。
- Policy Decision。
- Interrupt Created/Resumed。
- Tool Started/Completed。
- Checkpoint Saved/Loaded。
- Recovery Attempted。

日志不得记录：

- API Key。
- 完整用户敏感数据。
- 未脱敏的工具凭据。

### 9.2 固定评测场景

| 场景 | 预期结果 |
|---|---|
| 正常工具调用 | 工具结果进入 Observation，最终回答完成 |
| 工具暂时失败 | 按策略重试或重新规划 |
| 工具永久失败 | 不无限重试，返回明确结果 |
| Planner 非法 JSON | 触发有限规划恢复 |
| 澄清后恢复 | 原 taskId 继续，之前 Step 不丢失 |
| 危险工具审批通过 | 只执行一次原 PreparedToolCall |
| 危险工具审批拒绝 | 不执行工具，Planner 获得拒绝 Observation |
| 应用重启恢复 | 从最近 Checkpoint 继续 |
| 重复 Resume | 不重复推进状态 |
| 工具结果未知 | 不盲目重复副作用操作 |
| 超时 | 保存终态并停止执行 |
| 取消 | 后续 Step 不再执行 |
| 超出上下文预算 | 摘要或截断后仍可完成任务 |

### 9.3 指标

- 任务完成率。
- 平均 Step 数。
- 平均 LLM 调用次数。
- Planner 非法输出率。
- 工具成功率。
- 恢复成功率。
- 重复工具调用拦截次数。
- HITL 等待和恢复次数。
- 平均上下文大小。
- StopReason 分布。

## 10. 分阶段实施路线

### Phase 0：架构基线与仓库隔离

交付：

- 独立仓库。
- 当前架构说明。
- 核心边界和不做清单。
- 本规划书。

完成标准：

- KoawaAgent 与 ragent 不共享 Git 历史。
- 主分支测试全部通过。
- README 能说明项目定位。

### Phase 1：Checkpoint 最小闭环

新增：

- `AgentTaskStatus`
- `AgentTaskSnapshot`
- `AgentCheckpointStore`
- `InMemoryAgentCheckpointStore`
- Snapshot Mapper
- Snapshot JSON Codec
- `AgentCheckpointService`
- revision 并发控制

改造：

- AgentLoop 每个 Step 完成后保存。
- 终态保存。
- 启动新任务时创建初始 Snapshot。

完成标准：

- 能加载最近 Snapshot。
- 已完成 Step 不会因恢复被重新执行。
- 终态任务不能继续。
- Snapshot 序列化往返不丢字段。

### Phase 2：跨进程持久化与 Resume

新增：

- `JdbcAgentCheckpointStore`
- Checkpoint 表结构和迁移脚本
- PostgreSQL Docker Compose
- Task 查询 API
- Resume API

完成标准：

- 进程停止并重新启动后能恢复任务。
- revision 冲突返回 409。
- 同一任务不能并发推进。

### Phase 3：Human-in-the-Loop

改造：

- `ASK_CLARIFICATION` 从终止动作改为动态 Interrupt。
- 增加 `WAITING_FOR_INPUT`。
- Resume 输入写回原任务上下文。

新增：

- `AgentInterrupt`
- `AgentResumeCommand`
- Interrupt 消费记录

完成标准：

- 澄清前后的 Step 属于同一 taskId。
- 重启后仍能提交澄清答案。
- 重复答案不会重复推进。

### Phase 4：审批与可靠工具执行

新增：

- `REQUIRE_APPROVAL`
- `PreparedToolCall`
- `ToolExecutionRecord`
- `ToolExecutionStore`
- `toolCallId`
- 请求参数哈希

完成标准：

- 危险工具在批准前不执行。
- 批准后只执行原工具和原参数。
- 拒绝后生成可供 Planner 使用的 Observation。
- 恢复时不重复已成功工具。
- 未知执行结果进入人工处理状态。

### Phase 5：上下文工程

新增：

- `AgentContextBudget`
- History/Observation 截断策略
- `ConversationSummarizer`
- Prompt 版本字段

完成标准：

- 大型工具结果不会无限进入 Prompt。
- 旧历史可压缩，新近信息保留。
- 错误关键信息不因压缩丢失。

### Phase 6：结构化 Trace 与评测

新增：

- 可查询的 Run/Step/Event Store。
- 固定评测数据集。
- 评测执行器和报告。

完成标准：

- 每个固定场景可重复运行。
- 能查看一次任务完整状态迁移。
- 能统计完成率、Step、LLM 和恢复指标。

### Phase 7：演示与交付

交付：

- 一个最小 MCP Demo。
- Docker 重启恢复演示。
- Runtime 架构图。
- LangGraph、Spring AI Alibaba 对比说明。
- README 使用指南。
- 简历描述和面试讲解稿。

Demo 必须覆盖：

```text
查询工具
→ 工具失败后重新规划
→ 危险操作请求审批
→ 执行并记录
→ 重启后恢复
→ 验证结果
→ 返回最终答案
```

## 11. 推荐开发顺序

严格按以下顺序实施：

1. 先定义任务状态和 Snapshot，不直接写数据库。
2. 使用 InMemory Store 跑通状态迁移测试。
3. 再实现持久化 Store 和重启恢复。
4. Checkpoint 稳定后再改 ASK_CLARIFICATION。
5. HITL 稳定后再增加审批。
6. 审批绑定 PreparedToolCall 后再实现工具幂等。
7. 最后增加上下文压缩、Trace 和评测。

不要同时开发 Checkpoint、HITL 和幂等；这三者共享状态模型，同时修改会难以判断故障来源。

## 12. 测试策略

### 单元测试

- 状态迁移。
- Snapshot 映射和序列化。
- revision 冲突。
- Policy 决策。
- toolCallId 和 requestHash。
- 上下文裁剪。

### 组件测试

- AgentLoop + InMemory Checkpoint。
- AgentLoop + ToolExecutionStore。
- Interrupt 创建和 Resume。
- JDBC Store。

### 故障注入测试

- Checkpoint 保存失败。
- 工具执行前崩溃。
- 工具执行后、记录结果前崩溃。
- Resume 重复提交。
- 并发 Resume。
- LLM 输出非法 Action。
- MCP 超时。

### 端到端测试

- HTTP 创建任务。
- 等待澄清或审批。
- 重启应用。
- Resume。
- 查询最终任务状态和 Steps。

## 13. 风险与控制

| 风险 | 控制方式 |
|---|---|
| Snapshot 随类结构变化无法读取 | schemaVersion + 显式迁移 |
| 重启后重复工具副作用 | 执行前记账 + toolCallId + OUTCOME_UNKNOWN |
| 同一任务并发恢复 | revision 乐观锁 |
| 用户重复提交审批 | interruptId 幂等消费 |
| 上下文无限增长 | ContextBudget + 摘要 + Observation 投影 |
| Planner 被工具结果中的提示词影响 | 工具结果标记为不可信数据 |
| 项目继续膨胀 | 每阶段按“不做清单”审查 |
| 过早引入数据库细节 | 先完成 Store SPI 和内存测试 |

## 14. Definition of Done

一个阶段只有同时满足以下条件才算完成：

- 代码通过格式检查和全部测试。
- 正常路径和失败路径都有测试。
- 公共接口有清晰状态语义。
- 没有绕过 AgentLoop 的旁路执行。
- 新增持久化数据有版本字段。
- 新增外部调用有超时和错误分类。
- README 或架构文档同步更新。
- 没有引入与当前阶段无关的平台能力。

## 15. 参考资料

- [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- [LangGraph Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)
- [LangGraph Functional API / Durable Execution](https://docs.langchain.com/oss/python/langgraph/functional-api)
- [Spring AI Alibaba Graph Persistence](https://java2ai.com/en/docs/frameworks/graph-core/core/persistence/)
- [Spring AI Alibaba Human-in-the-Loop](https://java2ai.com/docs/frameworks/graph-core/examples/human-in-the-loop/)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [LangChain4j Agentic](https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/agents.md)
