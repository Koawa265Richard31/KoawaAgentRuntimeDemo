# ADR-004：跨任务 Conversation History 持久化

## 状态

Accepted（2026-08-01，项目负责人通过“进行下一个切片”批准进入 M0-S5g 实现）

补充裁决（2026-08-01）：项目负责人要求后续统一协议及全路径适配必须在 M3 交付前完成；
Execution Plan v1.2 将最终 Gate 前移到 `M3-S1` 入口。若 Conversation 的物理 Item-row 无法完整
迁移，继续使用本 ADR 的 Turn-row 和明确 adapter；这不豁免核心 ModelTurn/ToolCall v2 门禁。

对应范围：

- `M0-S5`：用持久化 Conversation Store 替换进程内历史，并完成重启后的跨 Task 上下文恢复。
- `M0-S5e`：已建立 `AgentConversationStore` 领域端口与完整 Turn 的内存基线。
- 本 ADR 只决定数据边界、幂等身份、顺序和事务语义；不在本切片修改生产代码或数据库。

证据：

- E1：[LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
  将按 Thread 保存可恢复执行状态的 Checkpointer，与可跨 Thread 保存应用数据的 Store 分开。
- E2：[LangGraph BaseCheckpointSaver](https://github.com/langchain-ai/langgraph/blob/main/libs/checkpoint/langgraph/checkpoint/base/__init__.py)
  和 [BaseStore](https://github.com/langchain-ai/langgraph/blob/main/libs/checkpoint/langgraph/store/base/__init__.py)
  在源码层也保持执行恢复与跨 Thread 数据两个接口。
- E1：[OpenAI Agents SDK Sessions](https://openai.github.io/openai-agents-python/sessions/)
  把 Session 定义为一个 `session_id` 下可持久化、可裁剪读取的对话历史。
- E2：[OpenAI Agents SDK Session persistence](https://github.com/openai/openai-agents-python/blob/main/src/agents/run_internal/session_persistence.py)
  区分“本次送入模型的历史”和“本次运行后需要追加的新项”，并显式跟踪已经持久化的项。
- E1：[PostgreSQL Transactions](https://www.postgresql.org/docs/current/tutorial-transactions.html)
  支持把多个写操作组成全有或全无、对其他事务同时可见的原子单元。
- E1：[PostgreSQL Row-Level Locks](https://www.postgresql.org/docs/current/explicit-locking.html#LOCKING-ROWS)
  说明同一行上的更新会互斥等待，可用短事务串行分配同一 Conversation 的顺序号。
- E1：[PostgreSQL Unique Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html#DDL-CONSTRAINTS-UNIQUE-CONSTRAINTS)
  与 [INSERT ... ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT)
  提供数据库级唯一性和冲突处理基础。
- E1：[PostgreSQL LIMIT/OFFSET](https://www.postgresql.org/docs/current/queries-limit.html)
  要求配合确定性的 `ORDER BY` 才能得到稳定子集。

上述框架证据只证明“执行状态与会话历史应分离”“历史追加需要显式持久化边界”。它们没有替
KoawaAgent 证明跨 Store 原子性、Exactly Once 或并发顺序；这些结论仍须在实现切片用
PostgreSQL/Testcontainers 补 E3。这里是批准前的证据限制，不改变页首已经更新的 Accepted 状态。

## 背景

### Checkpoint 和 Conversation History 不是同一种数据

当前一个 Codex 对话中的每次新 `/chat` 会创建一个新 Task；同一个 `conversationId` 可以包含多个
`taskId`。因此两个持久化边界的职责是：

```text
taskId
  → Agent Checkpoint
  → 单个 Task 的状态、Steps、Interrupt、revision 和 Resume

conversationId + userId
  → Conversation History
  → 多个 Task 之间提供给下一次模型调用的有序 USER/ASSISTANT 上下文
```

Checkpoint 采用 latest-only Snapshot 是为了恢复任务，并不等于跨 Task 的会话日志。Conversation
History 也不能反过来承担 Runner 的精确恢复状态。

### 当前代码存在两个确定缺口

首次 Chat 的正常流程是：

```text
terminal Step 保存为 RUNNING Snapshot
  → PersistentAgentCheckpointLifecycle.completed(...)
  → Checkpoint 保存 COMPLETED / WAITING_FOR_INPUT
  → AgentChatFacade.appendTurn(question, answer)
```

`completed` 与 `appendTurn` 是两次独立动作。若进程在二者之间崩溃，数据库会表现为“Task 已
完成，但下一 Task 读不到这轮历史”。若追加已经成功而 HTTP 响应丢失，调用方重试时，现有
`appendTurn` 又没有稳定身份来区分重放和新 Turn。

Resume 路径还有更直接的遗漏：`AgentInterruptConsumptionService` 只把用户回复写入当前 Task
的 `historySnapshot`，`AgentResumeExecutionService` 在 `lifecycle.completed` 后直接返回。它不
调用 Conversation Store，所以 Resume 后产生的下一次澄清或最终答案不会成为跨 Task 历史。

### 当前 Snapshot 不能直接无损投影会话

`AgentTaskSnapshot.historySnapshot` 同时包含：

- 创建 Task 时从旧 Conversation 加载的历史副本；
- Resume 时追加的用户回复；
- 不包含与这些回复成对的历次澄清输出。

澄清输出位于 `StepSnapshot.observationContent`，而当前 `consumedUserInputStep` 只表达恢复边界，
没有保存被消费的 `interruptId` 和一个类型化的“本轮输入”。从不同 Task 的 latest-only Snapshot
重新扫描会重复继承旧历史，也无法可靠配对多次 ASK/Resume。因此 Snapshot 可以作为故障核对
材料，但不能作为 Conversation History 的查询真源。

## 决策驱动因素

- 进程重启后，新 Task 能读取同一 Conversation 最近的完整历史。
- 一个 USER 输入和一个可交付 ASSISTANT 输出必须作为完整 Turn 同时可见，不能出现半个 Turn。
- 首次 Chat、Resume 和 terminal-step recovery 必须走同一套提交语义。
- 同一个逻辑 terminal Step 被重放时不能重复追加；不同内容撞到同一身份时不能静默覆盖。
- 一个 Task 可以多次 `ASK_CLARIFICATION`，因此 `taskId` 本身不是 Turn 唯一键。
- Checkpoint revision 表达 Snapshot 版本，会因 Step、Interrupt 消费和生命周期保存而增长，不能
  充当逻辑 Turn 身份。
- 同一 Conversation 并发完成时必须有数据库定义的稳定顺序，不能依赖毫秒时间戳碰巧不同。
- 最近窗口读取后必须恢复 oldest-first，保持现有 Prompt 组装合同。
- Conversation 保留策略不能被 Checkpoint 删除策略隐式控制。
- M0 不引入 Kafka、Redis、事件溯源平台或生产级 Memory Summarization。

## 候选方案

### 方案 A：每条 Message 一行

每个 USER、ASSISTANT 消息分别写入一行，并用 `role + item_sequence` 排序。

优点：

- 能自然表达任意角色和未来更多消息类型。
- 与 OpenAI Agents SDK 的 Session item 模型接近。

缺点：

- 当前一个 `appendTurn` 必须执行两次 INSERT；没有额外事务和 Turn 分组时会暴露半个问答。
- 需要再设计 `turn_id`、Turn 完成标记、角色顺序和两行之间的幂等关系。
- M0 当前只持久化一个用户输入和一个可交付输出，灵活性尚未带来实际收益。

M1 必须升级 canonical Model/Context/RunItem 协议，但这不自动要求跨 Task Conversation 改成
message-row。只有未来需要持久化一个 terminal boundary 下多个独立可交付 item 时，才通过独立
ADR 重新评估物理表；M0 不采用。

### 方案 B：每个完整 Turn 一行

一行同时保存本轮用户输入和一个 `FINAL_ANSWER` 或 `ASK_CLARIFICATION` 输出，读取时展开成两条
`ChatMessage`。

优点：

- 单行天然保持 USER/ASSISTANT 成对可见，与现有 `appendTurn` 和“最多 10 个完整 Turn”一致。
- terminal Step 可以直接提供稳定的输出边界。
- 数据库唯一约束能直接表达幂等写入。

缺点：

- 不保存没有可交付输出的孤立输入。
- 未来若跨 Task Conversation 需要一个输入对应多个独立可交付 ASSISTANT item，需要升级投影或
  拆成 message/item 表。

该方案最符合 M0 现有语义，采用。M1 完成 canonical v2 协议升级后，只要 adapter 能从 canonical
deliverable message 稳定写入 Turn、并把旧 Turn 投影回模型上下文，它仍可作为跨 Task read model。

### 方案 C：从 Checkpoint 动态投影

查询一个 Conversation 下所有 Task 的最新 Snapshot，再从 `historySnapshot`、Steps 和 Interrupt
恢复 USER/ASSISTANT 历史。

优点：

- 不新增 Conversation 写模型，表面上没有双写。

缺点：

- 每个新 Task 都复制旧历史，直接扫描会产生重复消息。
- 多次 Resume 输入与 ASK Step 没有完整、持久的逐 Turn 关联。
- latest-only Snapshot 的 schema、保留和删除会绑死 Conversation 查询。
- 每次组装 Prompt 都要反序列化多个任务的大 JSON，读取成本随执行状态膨胀。

该方案无法从当前数据无歧义重建历史，不采用。Checkpoint 只保留为恢复真源和故障核对来源。

### 方案 D：Checkpoint 事务内 Outbox，再异步投影

在 terminal Checkpoint 事务内写 Outbox，由后台消费者幂等生成 Conversation Turn。

优点：

- 当 Checkpoint 与 Conversation 位于不同数据库或服务时仍可避免丢事件。

缺点：

- 读取存在最终一致延迟，需要消费者、重试、积压监控和清理。
- 当前两张表位于同一个 PostgreSQL，直接事务能用更少组件得到更强的读取一致性。

M0 不采用。若未来拆分存储，Outbox 是允许的演进方向；禁止退化为无恢复凭据的事后双写。

## 决策

选择方案 B：以独立的 Turn 表作为 Conversation History 真源，并将可交付 terminal Checkpoint 与
Turn 在同一个 PostgreSQL 事务中提交。

### 1. Turn 身份

一个持久化 Turn 对应“某个 Task 的某个可交付 terminal Step”：

```text
TurnId = (taskId, terminalStepIndex)
```

选择 Step index 而不是 revision：

- 同一 Task 可以先后产生多个 ASK，再产生 FINAL；这些 Step index 不同。
- terminal Step 保存后，即使生命周期修复又增加 revision，它的 Step index 仍不变。
- revision 是并发写保护，不是业务事件身份。

数据库必须建立 `UNIQUE (task_id, terminal_step_index)`。发生重复键时：

1. conversation、user、输入来源、输入内容、输出类型和输出内容全部相同：视为同一请求的幂等
   重放，返回既有 Turn。
2. 任一不可变业务字段不同：抛出类型化的一致性冲突，不得 `DO NOTHING`、覆盖或拼接。

幂等核对必须发生在分配新 `turnSequence` 之前。相同 Conversation 的 writer 先锁定 Head，再按
`(taskId, terminalStepIndex)` 查询并逐字段比较；已存在则跳过 sequence 分配和 INSERT，只有确认为
新 Turn 才推进 Head。唯一约束是最后一道并发防线，不是用异常代替正常重放分支：PostgreSQL 的
唯一冲突会让当前事务进入失败状态，不能在同一事务中捕获后继续查询并提交。

这只保证数据库中每个已提交 terminal boundary 最多一行，不宣称模型调用、工具副作用或整个
HTTP 请求 Exactly Once。

### 2. 类型化的当前 Turn 输入

terminal Step 只包含 ASSISTANT 输出。为了在正常完成和崩溃恢复时得到同一 USER 输入，恢复状态
必须显式携带当前 Turn 输入，而不能在提交时临时猜“最后一条 USER”：

```text
CurrentTurnInput
  content
  type = ORIGINAL_QUESTION | INTERRUPT_REPLY
  sourceInterruptId = null | 被本次回复消费的 interruptId
```

- 新 Task 初始化时，`content = originalQuestion`、类型为 `ORIGINAL_QUESTION`。
- 消费 USER_INPUT Interrupt 时，用 `command.userInput` 和匹配的 `interruptId` 原子替换该边界。
- 该边界必须进入可恢复 Snapshot，使 terminal Step 已保存后发生重启仍能生成同一个 Turn。
- 现有 `consumedUserInputStep` 继续用于区分“旧 ASK 已被消费”和“仍需恢复等待态”，不能代替
  `CurrentTurnInput` 的内容与来源。

后续协议切片应把 `AgentConversationStore.appendTurn` 改成接收不可变的类型化 Turn，而不是继续
接收四个缺少身份的字符串参数。

### 3. 持久化模型

实现切片按以下逻辑模型落 Flyway migration；最终约束名可以遵循项目命名习惯：

```text
agent_conversation_head
  conversation_scope_id    BIGINT identity，PRIMARY KEY
  conversation_id          VARCHAR(128)，NOT NULL
  user_id                  可空；空白在应用边界规范化为 null
  next_turn_sequence       BIGINT，NOT NULL，CHECK >= 0
  created_at / updated_at  TIMESTAMPTZ，NOT NULL
  UNIQUE NULLS NOT DISTINCT (conversation_id, user_id)

agent_conversation_turn
  conversation_scope_id    NOT NULL，引用 head
  turn_sequence            BIGINT，NOT NULL，CHECK > 0
  task_id                  VARCHAR(128)，NOT NULL
  terminal_step_index      INTEGER，NOT NULL，CHECK >= 0
  input_type               NOT NULL，ORIGINAL_QUESTION | INTERRUPT_REPLY
  source_interrupt_id      初始输入为空，Interrupt 回复为被消费的 ID
  input_content            TEXT，NOT NULL，非空白
  output_type              NOT NULL，FINAL_ANSWER | ASK_CLARIFICATION
  output_content           TEXT，NOT NULL，非空白
  committed_at             TIMESTAMPTZ，NOT NULL
  PRIMARY KEY (conversation_scope_id, turn_sequence)
  UNIQUE (task_id, terminal_step_index)
  CHECK ORIGINAL_QUESTION <=> source_interrupt_id IS NULL
  CHECK INTERRUPT_REPLY <=> source_interrupt_id IS NOT NULL 且非空白
```

使用内部 `conversation_scope_id` 是为了让缺少 userId 的匿名会话也能获得非空外键；
`UNIQUE NULLS NOT DISTINCT` 让 `(conversationId, null)` 只能有一个 Head，同时避免使用
`"anonymous"` 哨兵与真实同名 userId 碰撞。该语义依赖当前 PostgreSQL 16 基线，必须由
Testcontainers 验证。

Turn 不直接外键到 `agent_checkpoint.task_id`。`task_id` 在这里是来源和幂等身份；Checkpoint 与
Conversation 的保留周期不同，删除执行快照不得隐式级联删除用户会话历史。应用层 terminal
committer 负责保证来源 Task 存在且字段匹配。

### 4. 同一 Conversation 的顺序

在 terminal transaction 内：

1. 对 `(conversationId, userId)` 创建或取得唯一 Head，并按固定锁顺序锁定该 Head。
2. 在锁内按 `(taskId, terminalStepIndex)` 查询既有 Turn：payload 相同则直接复用，不推进 Head；
   payload 不同则抛一致性冲突并回滚。
3. 只有不存在既有 Turn 时，才用
   `UPDATE ... SET next_turn_sequence = next_turn_sequence + 1 RETURNING ...` 分配顺序。
4. 使用返回值插入 Turn；唯一约束继续拒绝跨 Conversation 等异常竞态。

更新同一 Head 行会取得行锁，所以同一 Conversation 的 terminal transaction 被短暂串行；不同
Conversation 仍可并行。`turnSequence` 定义为“数据库成功推进该 Conversation Head 的顺序”，
不是 HTTP 到达时间、模型开始时间或应用服务器时钟顺序。

Head 更新与 Turn INSERT 在同一事务内，回滚时二者一起回滚。消费者仍不得把顺序号当数量或要求
永远无洞，因为人工修复、删除和未来迁移都可能产生空缺。

该锁只规定持久化顺序，不阻止两个不同 Task 事先基于同一旧历史并发调用模型。若产品要求同一
Conversation 严格一次只运行一个 Task，需要单独设计 Conversation-level Claim；不把这项能力
伪装成本 ADR 已解决的问题。

### 5. terminal transaction

所有可交付结束路径必须汇合到一个 application-level terminal committer：

```text
terminal Step 已通过 revision CAS / Fenced Write 保存，Task 仍为 RUNNING
  → 开启短 PostgreSQL 事务
      → 校验 execution permit（Resume 路径）
      → 用 revision CAS / Fenced Write 保存 COMPLETED 或 WAITING_FOR_INPUT
      → 锁定 Conversation Head 并核对 Turn identity/payload
      → 新 Turn 才分配 turnSequence 并 INSERT；相同重放复用既有 Turn
    提交
```

- `FINAL_ANSWER` 的 `outputContent` 来自 terminal Step observation。
- `ASK_CLARIFICATION` 的 `outputContent` 必须与同一事务写入 Snapshot 的
  `pendingInterrupt.prompt` 完全相同；Interrupt ID 只生成一次。
- `CANCELLED`、`TIMED_OUT`、`ERROR`、`MAX_STEPS` 仍保存 Task 状态，但不生成 Conversation Turn。
- `AgentChatFacade` 不再拥有事后 `appendTurn`；首次 Chat 和 Resume 使用同一 terminal committer。
- `AgentSnapshotRecoveryService.repairTerminalStep` 也必须调用该 committer，不能只修 TaskStatus。
- Store 只执行已明确的 Turn 命令，不从 Snapshot 猜测业务生命周期。

同一 DataSource 下的 Spring 事务必须覆盖 Checkpoint Store、Fenced Writer、Conversation Head 和
Turn Store。仅给某个 Repository 方法加事务而让其他写在事务外执行，不满足本 ADR。

### 6. 崩溃语义

| 崩溃位置 | 可见状态 | 恢复动作 |
|---|---|---|
| terminal Step 保存前 | 最近已提交的非 terminal Snapshot | 按现有 Resume 规则从已提交边界继续 |
| terminal Step 已保存，terminal transaction 开始前 | terminal Step + `RUNNING`，没有 Turn | Recovery 识别 terminal Step，重新执行同一 terminal transaction |
| terminal transaction 中途失败或进程退出 | Checkpoint 终态与 Turn 都回滚 | 与上一行相同 |
| terminal transaction 已提交，HTTP 响应丢失 | Checkpoint 终态与 Turn 同时存在 | 查询返回已提交状态；重复 Turn identity 只允许相同 payload |
| 相同 Turn identity、不同 payload | 事务整体回滚，Task 保持可诊断的旧边界 | 抛类型化一致性错误，禁止自动覆盖 |

因此允许存在的中间态仍是现有可恢复状态：“terminal Step 已保存，但 Task 还是 RUNNING”。不再允许
新产生“Task 已 COMPLETED/WAITING，但对应 Turn 缺失”的持久化窗口。

### 7. 读取窗口

M0 延续最多 20 条 Message，即最近 10 个完整 Turn，但只在读取时裁剪，不在每次追加时删除旧行：

```sql
SELECT ...
FROM (
    SELECT ...
    FROM agent_conversation_turn
    WHERE conversation_scope_id = ?
    ORDER BY turn_sequence DESC
    LIMIT 10
) recent
ORDER BY turn_sequence ASC;
```

查询获得最新 10 个 Turn 后，再逐行展开为 `USER(inputContent)`、
`ASSISTANT(outputContent)`。这样既使用尾部窗口，又保持 Prompt 所需的 oldest-first，并且永远不
裁出半个 Turn。

索引至少覆盖 `(conversation_scope_id, turn_sequence DESC)` 和
`(task_id, terminal_step_index)`。M6 Context Engine 可以把固定 10 Turn 替换为 token/字符预算，
无需改变持久化真源。

### 8. 保留、安全与可观测性

- M0 默认保留所有已提交 Turn；窗口只影响读取，不等于物理删除。
- Conversation 删除必须是显式用例，不能由 Checkpoint `delete(taskId)` 级联触发。
- `userId + conversationId` 是数据隔离条件，不得只凭 conversationId 返回其他用户内容。
- USER/ASSISTANT 内容属于敏感数据，不写入常规日志；日志只记录 taskId、terminalStepIndex、
  turnSequence、outcome 和冲突类型。
- M0 不实现加密、TTL、归档或 GDPR 删除工作流，但这些缺口必须保留在里程碑风险清单。

### 9. 协议升级与 M3 入口门禁

M0 的 Turn-row 只解决当前跨 Task 可见历史，不能代替 M1 的统一协议。后续必须保持四个边界：

```text
Provider OutputItem
  ├─→ ModelContextItem：当前 Task 下一轮模型要看到的 message/tool-call echo/tool-result
  └─→ Runtime RunItem：taskId/runId/sequence 下的模型、Policy、工具、验证运行事实

canonical deliverable USER / ASSISTANT
  └─→ ConversationTurn：跨 Task 的用户可见持久化投影

RunItem
  └─→ TraceEvent：后续持久化、查询和流式交互表示
```

`RunItem` 不能全量回灌 Prompt，也不能临时充当 Conversation 表；Conversation Turn 又不能代替
当前 Task 的 tool-call/tool-result context 或运行审计。

Execution Plan v1.2 新增 `M1-S7`。进入 `M3-S1` 前，“完整适配”至少满足：

- 已注册 Provider Adapter 无损输出 canonical `ModelTurn`，默认 Runtime 不再走
  `String → AgentActionParser` 主路径。
- 一个 ModelTurn 的多 ToolCall 保留 callId 和顺序，ToolResult 能关联原 callId。
- v2 loop 产生有序 RunItem；Snapshot v2 能重建当前 Task 的 message/tool context，v1 Snapshot
  有兼容读取或明确迁移。
- 首次 Chat、ASK、Resume、FINAL、terminal-step recovery 都从 canonical deliverable message
  写入同一个 Turn 真源；重启后 Turn 又能投影为 canonical ModelContextItem。
- typed stream delta 可以确定性聚合为与非流式等价的 ModelTurn；SSE/WebSocket 传输仍留在 M8。
- 固定回放、旧数据兼容和 PostgreSQL 重启/事务测试通过，Testcontainers skip 不算通过。

Fallback 只允许保持 Conversation 的当前物理语义：

- 协议尚未稳定时不做 dual writer、半切换或猜测性的 Message/Item-row migration。
- Turn-row 继续作为唯一跨 Task Conversation 真源，旧 `ChatMessage` 只在反腐 adapter 内出现。
- `AgentRunResult` 继续作为 REST 结果投影，不成为持久化协议。
- 物理 item-row 不是 M3 Gate 的完成指标；完整 adapter 可以继续使用 Turn-row。
- 核心 `ModelTurn/ToolCall` v2 未完成时必须返回 M1-S7，不得借 fallback 进入 M3 建第二套工具链。

因此“先采用符合当前语义的方案”保证 M0 可交付且不做半套迁移；“后续协议必须升级”则由
M1-S7 和 M3 入口 Gate 强制执行，两者不是互相替代关系。

## 后果

正向后果：

- Conversation History 与 latest-only Checkpoint 各自拥有清晰真源。
- 完整 Turn 单行保存，读取者不会观察到半个问答。
- 初次 Chat、Resume 和 terminal repair 共享相同幂等与崩溃语义。
- `(taskId, terminalStepIndex)` 能区分同一 Task 的多次澄清和最终答案。
- 同一 Conversation 的数据库顺序稳定，最近窗口可以确定性读取。

代价与限制：

- 增加 Head、Turn 两张表和一个跨 Repository 的事务服务。
- 同一 Conversation 的最终提交会在 Head 行上短暂串行。
- Snapshot 需要新增可恢复的 `CurrentTurnInput` 协议及兼容处理。
- 当前内存历史无法迁移；部署前已经丢失的数据也不能从 Snapshot 无歧义补回。
- 本决策不解决同一 Conversation 在模型执行阶段的并发，也不提供工具 Exactly Once。
- M1 必须完成 canonical ModelContextItem/RunItem/Streaming 协议及全路径 adapter；Turn-row 是否物理
  升级为 Message/Item-row 由可交付 Conversation 语义决定，不再把“换表”误当成协议完成。

## 验证计划

### 协议和组件测试

- 类型化 Turn 拒绝空身份、负 step、空输入/输出和不匹配的 input type/source。
- 直接执行非法 SQL 时，数据库 `NOT NULL/CHECK` 也拒绝半 Turn、非法 type/source 和空内容。
- 同一 `(taskId, terminalStepIndex)` + 相同 payload 返回既有 Turn。
- 相同 payload 重放不推进 Conversation Head、不制造无意义 sequence 空洞。
- 同一身份 + 不同 payload 抛类型化一致性冲突。
- 一个 Task 的 ASK、ASK、FINAL 三个不同 Step 能保存三个 Turn。
- 最近 10 个 Turn 展开为 20 条 Message，顺序 oldest-first。
- null/blank userId 规范化后互通，真实 `userId="anonymous"` 不与匿名会话碰撞。
- Resume 消费时保存 `CurrentTurnInput`，重启恢复后仍得到同一 input/source。

### PostgreSQL/Testcontainers（必须补 E3）

- Flyway 从 V1/V2 升级后创建 Head、Turn、唯一约束、外键和索引。
- 真实 PostgreSQL 验证 `UNIQUE NULLS NOT DISTINCT` 的匿名 Conversation 唯一性。
- 50 个同 Conversation 并发 Turn 获得唯一、严格递增的 sequence；不同 Conversation 不共用 Head
  锁。
- 在 Checkpoint CAS/Fenced Write、Head 更新、Turn INSERT 各位置注入异常，确认整个 terminal
  transaction 回滚。
- 模拟“terminal Step 已提交后进程退出”，重建 Spring Context 并恢复，确认终态和 Turn 只提交
  一次。
- 模拟事务提交成功但响应丢失，确认重放不产生重复 Turn。
- 重启后新 Task 读取前一个 Task 的 FINAL；ASK → Resume → FINAL 能读取全部三个完整 Turn。
- 并发冲突、Lease 过期接管和旧 Worker 迟到写回仍受 fencing token 保护。

H2 或 Mock 只可验证组件协议，不得把结果表述为 PostgreSQL 事务、行锁或 Flyway 已验证。

### M1-S7 协议 Gate 验证

- M0 Turn 能投影为 canonical USER/ASSISTANT ModelContextItem，顺序与内容不变。
- canonical deliverable message 在首次 Chat、Resume 和 terminal recovery 中产生相同 Turn identity。
- 多 ToolCall 的 ModelContextItem/RunItem/ToolResult 关联在 Snapshot v2 重启后不丢失。
- v1 Snapshot 与旧 Turn-row fixture 能由 v2 主路径读取；旧公共 REST JSON 保持兼容。
- 主 Runtime 测试证明不再调用 `AgentActionParser`；兼容路径必须单独命名和测试。
- 固定流式 delta 聚合结果与非流式 ModelTurn 相同。
- `M1-S7` 任一项未通过时，M3 Gate 返回失败且不注册 M3 Coding Tools。

### 待补验切片

为遵守单切片文件数与故障语义边界，批准 ADR 后拆为：

1. `M0-S5g`（已完成）：类型化 Turn/Input 协议、Flyway schema、JDBC Conversation Store 与
   PostgreSQL 幂等/排序测试。
2. `M0-S5h`（已完成）：统一 terminal committer，接入初次 Chat、Resume、terminal repair，并完成
   PostgreSQL 事务故障注入测试。
3. `M0-S5i`（已完成）：真实 PostgreSQL 重启 E2E、跨 Task 历史验收、旧 Chat/Cancel API
   合同回归与 README/M0 出口证据收口。
4. `M1-S7`：按 Execution Plan v1.2 完成 canonical v2 主路径、Snapshot/Resume/Conversation adapter、
   typed stream fixture 与 M3 入口 Gate；它不并入 M0 实现提交。

每个切片独立提交；任何 PostgreSQL 用例因 Docker 跳过时必须明确标记“尚未验证”，不能完成 M0。

### M0-S5i 实现证据（2026-08-03）

`PostgresAgentRestartE2ETest` 使用同一个 PostgreSQL 16.14 容器，依次创建并关闭三套完整 Spring
Context；每套 Context 都拥有新的 Hikari DataSource，运行时只注册 JDBC Conversation Store：

1. Context A 保存 `RUNNING revision 1 + terminal ASK Step` 后故意不调用 `completed` 并退出，
   此时 Head/Turn 都不存在。
2. Context B 通过生产 Resume Claim/Recovery 把它修复为 `WAITING_FOR_INPUT revision 2 + Turn 1`，
   Planner/LLM 调用次数为 0；相同旧 revision 重试被 CAS 拒绝且不增加 Turn。随后消费 Interrupt
   得到 revision 3，保存 FINAL Step 得到 revision 4，terminal transaction 得到
   `COMPLETED revision 5 + Turn 2`。
3. Context C 从同一数据库读取四条有序历史消息，再通过生产 Chat Facade 创建新 Task；Planner
   请求实际收到旧历史，新 Task 以 `revision 2 + Turn 3` 完成。

数据库最终逐行验证了 `turn_sequence=1..3`、`taskId + terminalStepIndex`、input/output type、
Interrupt reply 的真实 `sourceInterruptId` 和完整内容。旧 Chat/Cancel HTTP 合同另由
`AgentChatControllerTest` 覆盖。当时未先 clean 的报告目录汇总为 222 tests / 53 reports；
M0-S4a 后续审计确认其中混有两个已删除或已移动测试的 7 个陈旧用例。clean 后当前可信基线是
215 tests / 51 reports，全部 0 failure、0 error、0 skipped；该纠正不影响 S5i 的 PostgreSQL
重启断言和真实容器证据。

这完成 ADR-004 的重启和跨 Task 证据。ADR-003 延后的 `M0-S4a` 已于 2026-08-04 完成：普通
`mvn test` 无需手传 Docker API 参数即可实际运行全部 PostgreSQL 测试，因此 M0 的最后一个已知
出口阻塞项已经关闭。等待用户输入是否暂停 `deadlineAt` 仍是显式产品语义待决项，不能由本 E2E
的一小时测试 timeout 代替设计结论。

## 回滚方案

- ADR 在 Accepted 前可直接修改或拒绝，不产生运行时迁移。
- 在 migration 和 terminal transaction 尚未进入共享环境前，可以整体回滚当前实现提交；本地
  单元测试仍可使用内存实现，但它不是生产降级路径。
- 一旦共享环境开始使用原子 terminal transaction，禁止只把 Conversation Bean 切回内存实现：
  内存写无法加入 PostgreSQL 事务，会立即重新制造双写不一致。
- 已启用路径出现缺陷时，先通过流量门禁停止新的 Chat/Resume 可交付提交，保留已执行 migration，
  再新增应用版本或向前修复 migration；不得改写 Flyway 历史文件。
- 不允许回滚为 `completed()` 后由 Facade 无凭据追加的双写流程。
