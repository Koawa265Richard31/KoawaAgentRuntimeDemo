# KoawaAgent Coding Agent Harness 研究协议

> 项目：`D:\KoawaAgent`
>
> 文档定位：把 KoawaAgent 从“功能复刻项目”约束为“可验证的 Harness 研究项目”
>
> 使用对象：项目负责人、编码代理（Codex、Claude Code 等）、代码审查者
>
> 版本：v1.1
>
> 日期：2026-07-25（v1.1 修订：2026-07-26，见 `.agents/CHANGELOG.md`）

---

## 1. 研究目标

KoawaAgent 不以“复制某个 Coding Agent 的全部功能”为首要目标，也不以让模型综合多个产品后直接生成统一架构为研究方法。

项目目标是：

> 选择 Coding Agent Harness 中最关键的机制，通过阅读原始实现、提出假设、构造最小实验、注入失败、比较结果和形成 ADR，建立项目负责人自己的工程判断。

评价项目价值时，优先级如下：

1. 能否准确说明一个 Harness 机制解决了什么问题。
2. 能否说明替代方案及其失败模式。
3. 能否用测试、Trace 或指标验证结论。
4. 能否说明 KoawaAgent 为什么采用或拒绝某个设计。
5. 最后才是功能覆盖数量。

---

## 2. 研究与实施必须分离

每个机制必须经过以下阶段：

```text
Research Question
    ↓
Primary-source Reading
    ↓
Current-system Evidence
    ↓
Competing Hypotheses
    ↓
Minimal Experiment
    ↓
Failure Injection
    ↓
Measured Result
    ↓
ADR
    ↓ 人工批准
Implementation Slice
    ↓
Post-implementation Review
```

未经研究和 ADR 批准，不允许 Codex 直接实现大规模设计。

### 2.1 Research 不等于资料汇总

无效研究：

- 罗列多个框架的功能清单。
- 让模型给出“最佳实践”后直接采用。
- 只阅读二手博客和宣传资料。
- 只跑 Happy Path Demo。
- 用代码量或抽象层数量证明设计成熟。

有效研究：

- 明确一个可证伪的问题。
- 引用具体源代码、协议或官方文档。
- 在 KoawaAgent 中复现实际失败。
- 比较至少两个可实现方案。
- 记录与预期不一致的结果。
- 形成可被后续实验推翻的结论。

---

## 3. 证据等级

设计结论必须标注证据等级。

| 等级 | 证据 | 用途 |
|---|---|---|
| E0 | 推测、模型回答、未验证记忆 | 只能生成待验证问题 |
| E1 | 官方产品文档、协议规范 | 说明公开语义 |
| E2 | 官方开源代码、测试、变更记录 | 说明真实实现 |
| E3 | KoawaAgent 最小复现实验 | 说明本项目中的行为 |
| E4 | 故障注入、对照实验、指标 | 支撑架构决策 |
| E5 | 长期回归数据或真实任务样本 | 支撑稳定结论 |

规则：

- E0 不能直接产生 ADR 决策。
- 引入关键依赖至少需要 E2。
- 改变 Checkpoint、权限或工具副作用语义至少需要 E3。
- 宣称“更可靠”“更高效”“更安全”至少需要 E4。

泄压阀（2026-07-26 新增，防止单人项目决策卡死）：

- 低风险且可逆的实现决策（不涉及持久化 schema、外部副作用、权限边界），
  允许以 E2 证据先行实施，事后补 E3/E4 验证；ADR 中必须标注"待补验"。
- 高风险决策（副作用恢复、权限、数据迁移）维持原门禁，不适用本条。

---

## 4. Codex 在研究中的职责

Codex 可以：

- 帮助定位官方文档和开源实现。
- 提取相关类、协议和测试。
- 对比多个候选方案。
- 生成最小实验脚手架。
- 补充边界测试和故障注入点。
- 作为反方挑战当前结论。
- 整理实验数据和生成初稿。

Codex 不得替代项目负责人：

- 定义最终研究问题。
- 决定项目真正要优化的指标。
- 选择最终架构。
- 批准 ADR。
- 解释未经验证的闭源内部机制。
- 将推测包装成事实。
- 因为实现方便而修改研究目标。

每次 Codex 研究输出都必须分开标识：

```text
已验证事实
合理推断
未知项
需要实验验证的假设
```

---

## 5. 单项研究模板

每个 Harness 机制创建一份研究记录：

```text
docs/research/RNNN-topic-name.md
```

使用以下模板：

```markdown
# RNNN：研究主题

## 1. 研究问题

必须是可验证问题，不是宽泛主题。

## 2. 研究价值

说明它影响的可靠性、性能、成本、权限或用户体验。

## 3. 当前 KoawaAgent 行为

列出代码位置、测试和实际 Trace。

## 4. 参考对象

### 对象 A
- 官方文档：
- 源代码：
- 测试：
- 可确认事实：
- 无法确认内容：

### 对象 B
...

## 5. 候选方案

### 方案 A
- 核心机制：
- 优点：
- 缺点：
- 失败模式：

### 方案 B
...

## 6. 假设

H1：
H2：
H3：

## 7. 最小实验

- 输入：
- 环境：
- 控制变量：
- 操作：
- 预期：
- 指标：

## 8. 故障注入

- 崩溃点：
- 并发条件：
- 超时：
- 重复请求：
- 权限失败：

## 9. 实验结果

必须填写真实结果，不使用“应该”。

## 10. 结论

- 接受/拒绝哪些假设：
- 推荐方案：
- 仍未知：

## 11. ADR 建议

## 12. 后续实现切片
```

---

## 6. 推荐研究主线

### R001：模型回合协议

研究问题：

> 文本 JSON Action、Provider 原生 Tool Use 和统一最低公分母协议，在正确性、可恢复性和多 Provider 兼容上有什么差异？

参考对象：

- OpenAI Responses / Agents SDK。
- Anthropic Messages / Claude Code 可确认的公开接口。
- OpenCode Provider Adapter。
- KoawaAgent 当前 `LlmAgentPlanner + AgentActionParser`。

实验：

- 单 ToolCall。
- 一轮多个 ToolCall。
- 文本与 ToolCall 混合。
- 流式参数中断。
- Provider 重试后 call ID 是否稳定。
- 无效 arguments。

产出：

- `ModelTurn` ADR。
- Provider metadata 保留原则。

### R002：Agent Loop 终止语义

研究问题：

> 完成、暂停、审批、预算耗尽、模型失败和基础设施失败应如何分离？

实验：

- FinalOutput。
- ASK_CLARIFICATION。
- MAX_STEPS。
- Checkpoint 保存失败。
- 工具执行后模型调用失败。
- 取消与超时竞争。

产出：

- StopReason/TaskStatus/RunOutcome 对照。

### R003：Checkpoint 与工具副作用

研究问题：

> 仅在完整 Step 边界保存，能否保证 Coding Agent 崩溃恢复时不重复副作用？

候选：

- Step Checkpoint。
- Write-ahead Tool Ledger。
- 幂等键。
- OS 进程查询。
- 人工处理 `OUTCOME_UNKNOWN`。

实验：

```text
工具成功
  → 结果未持久化
  → 强制终止进程
  → Resume
```

分别测试：

- 文件读取。
- Patch。
- Shell build。
- Git commit。
- 外部 MCP 写操作。

### R004：Workspace 边界

研究问题：

> 应用级路径校验、系统权限和容器沙箱分别能防住什么，不能防住什么？

实验：

- `..`。
- 符号链接/Junction。
- Shell 绝对路径。
- 子进程。
- 环境变量泄露。
- 网络访问。

产出：

- 威胁模型。
- Sandbox ADR。

### R005：Patch 语义

研究问题：

> 直接覆写、文本 Patch、AST 编辑和 LSP Workspace Edit 的可靠性边界分别是什么？

实验：

- 用户在 Agent 读取后修改文件。
- 换行符变化。
- 非 UTF-8。
- 多文件 Patch 中一项失败。
- 相同文本出现多次。

### R006：Shell 与持久进程

研究问题：

> 一次性 Process、PTY、后台进程和远程 Sandbox 应如何统一为可恢复 Tool Result？

实验：

- 超时。
- 大量 stdout。
- stdout/stderr 交错。
- 需要 stdin。
- 开发服务器不退出。
- Agent Runtime 重启。

### R007：上下文选择

研究问题：

> 最近消息、检索式上下文、项目规则、计划状态和压缩摘要如何组合，才能降低成本而不丢失任务约束？

实验：

- 长工具输出。
- 多次失败后压缩。
- 用户约束位于早期消息。
- 项目规则与仓库 Prompt Injection 冲突。
- Compaction 后继续修改。

指标：

- 完成率。
- Token。
- 约束保持率。
- 重复读取次数。

### R008：完成验证

研究问题：

> Coding Agent 的 FinalOutput 是否应该被 Runtime 接受，还是必须通过确定性 Finalization Gate？

实验：

- 模型声称完成但测试失败。
- 测试通过但存在未提交 diff。
- 编译通过但任务要求未满足。
- 测试命令本身缺失。

### R009：多 Agent 隔离

研究问题：

> 同一进程直接调用、共享目录多 Agent、只读 Agent、独立 Worktree Agent 和 A2A Remote Agent 的一致性成本如何不同？

实验：

- 两个 Agent 修改同一文件。
- Reviewer 读取过时 diff。
- Worker 失败后主 Agent 恢复。
- 上下文和密钥是否越界共享。

规则：

- 本研究完成前不实现共享可写目录的并行 Agent。

### R010：Harness Evaluation

研究问题：

> 如何判断 Harness 改进，而不是模型或 Prompt 偶然变好？

要求：

- 固定模型与参数。
- 固定任务和仓库版本。
- 记录随机性。
- 重复运行。
- 对比成功率、成本、步骤、恢复和越权。

---

## 7. 主流 Harness 学习重点

### 7.1 OpenCode

重点学习：

- Provider 抽象。
- Session 和消息持久化。
- TUI 与 Agent Runtime 分离。
- 工具集合。
- LSP/诊断。
- Primary Agent/Subagent 使用体验。

避免：

- 仅复制工具名称。
- 因为 OpenCode 支持多 Provider 就过早抽象全部厂商能力。

### 7.2 Codex

重点学习：

- Workspace 与沙箱策略。
- 审批策略。
- Tool/Run Item 事件。
- Patch 与命令控制。
- 长任务与可恢复执行。
- 主 Agent/并行任务的工作区隔离。
- 评测对 Harness 迭代的作用。

避免：

- 推测闭源服务端实现。
- 把公开行为直接解释成内部架构事实。

### 7.3 Claude Code

重点学习：

- 项目规则与分层记忆。
- Permission mode。
- Hooks。
- Skills/Commands。
- MCP。
- Session resume。
- 上下文压缩和 Subagent 使用方式。

避免：

- 将 CLI 行为和 Anthropic 模型能力混为一谈。

### 7.4 LangGraph

重点学习：

- Checkpoint。
- Interrupt。
- Thread/State。
- Durable Execution。
- State 与 Long-term Store 分离。

不学习：

- 为 KoawaAgent 强行实现通用 Graph DSL。

### 7.5 Temporal/Dapr/Restate

重点学习：

- 长期等待。
- Retry/Timer/Signal。
- 进程崩溃恢复。
- 确定性 Workflow 与非确定性 Agent activity 的边界。

不学习：

- 把每个模型 Turn 都拆成重量级分布式工作流。

---

## 8. 实验纪律

### 8.1 一次只改变一个主变量

禁止同时改变：

- 模型。
- Prompt。
- Tool schema。
- Context policy。
- Loop。
- Sandbox。

如果同时改变，结果不能归因。

### 8.2 保留失败样本

失败轨迹不得只修复后删除。至少保存：

- 输入。
- 模型和参数。
- Prompt/Policy 版本。
- Tool calls。
- Tool results。
- Checkpoint。
- 最终状态。
- 失败分类。

### 8.3 不用单次成功证明设计

至少满足以下之一：

- 同场景多次重复。
- 多场景通过。
- 失败注入通过。
- 与基线形成明显对照。

### 8.4 实验代码与产品代码分离

实验性实现可以位于：

```text
src/test
experiments
docs/research
```

进入生产路径前必须：

- ADR 批准。
- API 稳定。
- 完成迁移设计。
- 通过回归。

---

## 9. 研究完成门禁

一个研究主题完成必须具备：

- 明确研究问题。
- 至少两种方案。
- 证据等级标注。
- KoawaAgent 当前行为证据。
- 最小实验。
- 至少一个失败注入。
- 真实结果。
- 未知项。
- 推荐或拒绝意见。
- 对应 ADR。
- 可独立实施的 Coding Slice。

缺少任一项时，Codex 只能继续研究，不能进入生产实现。

研究时间盒（2026-07-26 新增）：

- 每个 R 主题默认预算：3 个最小实验或两个自然周（以先到者为准）。
- 超预算时必须收敛：产出带"未知项清单"的 ADR 草案交人工裁决，
  不允许无限延长研究；未知项转化为后续独立的小型验证切片。
- 项目负责人可以为单个主题显式延长预算，但必须记录延长原因。

---

## 10. 面试材料沉淀

每个研究主题最终额外准备：

1. 30 秒问题说明。
2. 2 分钟设计比较。
3. 5 分钟故障案例。
4. 一张状态/时序图。
5. 一段核心代码。
6. 一个失败测试。
7. 一个被否决方案。
8. 一个仍未解决的问题。

面试表达重点：

```text
我研究了什么问题
  → 我如何证明它存在
  → 我比较了哪些方案
  → 哪个实验推翻了我的预期
  → 我最终为何这样设计
```

不以“代码由我逐字符手写”作为价值证明，而以“问题、实验和取舍由我真正掌握”作为价值证明。

---

## 11. 首个研究任务

建议从当前代码直接开始：

```text
R003：Checkpoint 与工具副作用
```

原因：

- KoawaAgent 已完成 Step Checkpoint。
- Resume 尚未完全闭环。
- 当前最容易通过崩溃注入形成真实理解。
- 研究结果会直接约束 Tool Ledger、Patch、Shell 和审批设计。

在 R003 完成并形成 ADR 前，不应让 Codex直接实现完整 Tool Execution Ledger。
