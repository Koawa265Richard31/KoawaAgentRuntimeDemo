# KoawaAgent Codex 操作手册

> 项目：`D:\KoawaAgent`
>
> 文档定位：规定编码代理在研究、设计、编码、审查和故障处理中的工作方式
>
> 版本：v1.1
>
> 日期：2026-07-25（v1.1 修订：2026-07-26，见 `.agents/CHANGELOG.md`）
>
> 适用对象：任何编码代理（Codex、Claude Code 等）。文中"Codex"泛指编码代理。

---

## 1. 三份指导文档与优先级

Codex 开始工作前必须读取：

1. `KoawaAgent-Harness-Research-Protocol.md`
2. `KoawaAgent-Coding-Harness-Codex-Execution-Plan.md`
3. `KoawaAgent-Codex-Operating-Guide.md`

三份文档职责：

| 文档 | 负责问题 |
|---|---|
| Harness Research Protocol | 为什么研究、如何形成自己的设计结论 |
| Coding Harness Execution Plan | 做什么、按什么里程碑和切片实现 |
| Codex Operating Guide | Codex 本轮可以怎么工作、必须何时停止 |

指令优先级：

```text
用户当前明确指令
  → 安全与权限要求
  → Research Protocol
  → Execution Plan
  → Operating Guide
  → 现有项目习惯
```

当文档冲突时，Codex 不得自行选择对自己更方便的解释，必须指出冲突并停止。

---

## 2. 工作模式

每轮任务必须明确处于一种模式。

```text
RESEARCH
DESIGN
IMPLEMENT
REVIEW
INCIDENT
```

未明确模式时，Codex 默认使用 `RESEARCH`，不得修改生产代码。

### 2.1 RESEARCH

允许：

- 阅读代码和文档。
- 检索官方来源。
- 运行只读诊断。
- 创建研究记录或实验建议。
- 在测试/实验目录创建最小复现，但必须先说明。

禁止：

- 修改生产代码。
- 引入依赖。
- 改数据库。
- 提交实现。

产出：

- 已验证事实。
- 推断。
- 未知项。
- 候选方案。
- 实验计划。

### 2.2 DESIGN

允许：

- 编写 ADR。
- 定义接口草案。
- 绘制状态图和时序。
- 分解 Coding Slice。

禁止：

- 默认 ADR 已批准。
- 修改生产代码。
- 通过“先实现再讨论”推动决策。

产出：

- 候选方案。
- 迁移影响。
- 失败语义。
- 推荐方案。
- 等待人工批准。

### 2.3 IMPLEMENT

前置条件：

- 有明确 Slice 编号。
- 对应 Research/ADR 已完成或该 Slice 不涉及架构决策。
- 用户明确授权实现。

"涉及架构决策"的判定标准（满足任一即是，需先走 Research/ADR）：

- 改变公共 API 或事件协议。
- 改变持久化 schema 或 Snapshot 结构。
- 改变任务状态机或恢复语义。
- 引入新依赖。
- 改变权限、审批或沙箱边界。
- 引入新的外部副作用类型。

均不满足时视为普通实现切片，无需 ADR。

允许：

- 修改 Slice 允许的文件。
- 增加对应测试。
- 更新开发记录。
- 在授权时创建本地提交。

禁止：

- 跨 Slice。
- 改变研究结论。
- 顺手重构。
- 自动 Push。

### 2.4 REVIEW

默认只读。

产出按严重级别：

```text
P0：数据丢失、安全或大面积不可用
P1：核心语义错误、恢复错误、越权
P2：边界缺陷、兼容风险、测试不足
P3：维护性或非阻断改进
```

每个问题必须包含：

- 文件和行号。
- 触发条件。
- 实际后果。
- 为什么现有测试未覆盖。
- 建议方向。

禁止在 Review 模式直接修复，除非用户随后授权。

### 2.5 INCIDENT

适用于：

- 测试回归。
- 数据迁移失败。
- Checkpoint 损坏。
- 并发推进。
- 重复工具副作用。
- 环境或依赖故障。

处理顺序：

```text
停止新功能
  → 保留现场
  → 复现
  → 分类
  → 最小修复
  → 原失败测试
  → 相关测试
  → 全量回归
  → 事后记录
```

---

## 3. 每轮启动协议

Codex 必须先执行只读检查：

```text
git status --short
git branch --show-current
git log -5 --oneline
```

然后读取：

- 三份 `.agents` 指导文档。
- `README.md`。
- 当前 Slice 对应的规划章节。
- `docs/development/KoawaAgent-Development-Notes.md` 最近相关记录。
- 相关生产代码和测试。

开始工作前必须向用户汇报：

```text
模式：
研究/切片编号：
目标：
依据：
预计修改文件：
明确不修改：
测试计划：
停止条件：
```

没有这段汇报，不开始写代码。

---

## 4. 用户改动保护

工作区中的所有已有修改默认属于用户。

Codex 必须：

- 识别未跟踪文件。
- 识别暂存和未暂存修改。
- 判断是否与当前 Slice 重叠。
- 保留无关修改。

Codex 不得：

- `git reset --hard`。
- `git checkout -- <file>`。
- `git clean`。
- 覆盖用户文件。
- 把无关改动加入自己的提交。
- 因测试失败删除用户改动。

发生重叠时：

1. 停止写入。
2. 指出文件和重叠位置。
3. 给出可选处理方案。
4. 等待用户决定。

---

## 5. Scope 控制

规模、测试顺序与提交规则的单一事实来源是 Execution Plan §3.3、§3.4 与 §20；
本章仅为执行摘要，如有出入以 Execution Plan 为准（2026-07-26 修订）。

### 5.1 文件范围

默认单 Slice：

- 生产文件不超过 8 个。
- 测试文件不超过 8 个。
- Flyway migration 不超过 1 个。
- 不同时修改两个核心协议。

超过时必须：

- 停止。
- 解释为什么超过。
- 拆成更小 Slice。

### 5.2 语义范围

一个 Slice 只能有一个主语义，例如：

- 定义 Resume Command。
- 实现 Snapshot Restore。
- 消费 Interrupt。
- 定义 ModelTurn。
- 实现 Patch 原子性。

以下组合禁止放在同一 Slice：

- Provider 协议 + Agent loop 重写。
- Checkpoint + Tool Ledger。
- Sandbox + Terminal Runtime。
- Context Compaction + Subagent。
- 数据迁移 + 公共 API 删除。

### 5.3 依赖范围

引入新依赖前必须：

- 指向批准 ADR。
- 说明 license。
- 说明维护状态和版本。
- 说明替代方案。
- 说明移除成本。
- 增加最小隔离 Adapter。

没有 ADR 时，只能调研，不能修改 `pom.xml`。

---

## 6. 编码规则

### 6.1 先协议，后实现

涉及核心边界时按顺序：

```text
领域语义
  → 接口
  → 不变量测试
  → 内存实现
  → 持久化/外部实现
  → 集成
```

禁止先写 Controller 或数据库，再反推领域接口。

### 6.2 失败必须类型化

禁止用一个通用 RuntimeException 混合：

- 模型失败。
- 工具失败。
- Policy 拒绝。
- Checkpoint 失败。
- Snapshot 损坏。
- revision 冲突。
- 用户取消。

新异常必须说明：

- 谁能处理。
- 是否可重试。
- 是否改变 TaskStatus。
- 是否能安全 Resume。

### 6.3 Runtime 与持久化隔离

继续保持：

```text
Runtime State
  ↕ Mapper
Versioned Snapshot
```

禁止：

- 将 Spring Bean 放入 Snapshot。
- 直接序列化 Process/Client/Function。
- 在 Runner 中写 JDBC。
- 让 Store 推断业务生命周期。

### 6.4 工具路径唯一

所有工具必须通过：

```text
Registry
  → Schema Validation
  → Capability
  → Policy
  → Approval
  → Ledger
  → Executor
  → Observation/ToolResult
```

任何旁路调用都属于 P1 缺陷。

### 6.5 日志

不得记录：

- API key。
- Authorization header。
- 全量环境变量。
- 未脱敏 Secret。
- 用户私密内容的完整副本。
- 隐藏 reasoning。

日志必须带稳定 taskId/runId/toolCallId，但不能以 ID 代替授权检查。

---

## 7. 测试协议

### 7.1 测试层级

每个 Slice 至少考虑：

1. 不变量单元测试。
2. 正常路径。
3. 失败路径。
4. 边界路径。
5. 与上一 revision/旧协议兼容。
6. 并发或重复提交（如适用）。
7. 全量回归。

### 7.2 执行顺序

```text
单测试类
  → 当前包
  → 相关组件
  → mvn test
  → git diff --check
```

### 7.3 禁止的测试处理

- 删除失败测试。
- 减少关键断言。
- 增加 sleep 掩盖并发问题。
- 使用宽泛 catch 让测试通过。
- 把测试 disabled。
- 将真实集成语义全部替换为 Mock。

### 7.4 PostgreSQL

Testcontainers 依赖已在 `pom.xml` 提供（2026-07-26），
`PostgresJdbcAgentCheckpointStoreTest` 为参考实现；本地无 Docker 时该类测试
自动跳过，但 CI 必须运行。

涉及以下语义时必须有 PostgreSQL/Testcontainers 验证：

- revision/CAS。
- JSON 类型。
- 事务与锁。
- 时间和时区。
- Flyway。
- 并发 Resume。

H2 结果不得被描述为 PostgreSQL 已验证。

---

## 8. 工具和命令权限

### 8.1 默认允许的只读动作

- 搜索文件和文本。
- 读取 Git 状态和 diff。
- 读取项目配置。
- 运行不会写入仓库的诊断。

### 8.2 正常实现动作

在 IMPLEMENT 模式和当前 Slice 范围内可以：

- 编辑授权文件。
- 增加测试。
- 运行 Maven 测试。
- 更新开发文档。

### 8.3 需要明确授权

- 安装依赖。
- 网络下载。
- 启动容器。
- 修改数据库。
- 删除文件。
- Git commit。
- 创建 worktree。
- 执行外部副作用操作。

### 8.4 默认禁止

- Push。
- Force push。
- 删除分支。
- 清理用户工作区。
- 绕过 Sandbox/Approval。
- 将 Secret 写入配置。

---

## 9. 提交协议

只有用户明确要求提交时才提交。

提交前：

```text
git status --short
git diff --check
git diff --stat
mvn test
```

Codex 必须列出：

- 将要提交的文件。
- 不会提交的用户文件。
- 测试结果。
- 提交信息。

提交信息只表达一个意图。

提交后：

- 报告 commit ID。
- 再次报告剩余工作区状态。
- 不开始下一 Slice。

---

## 10. Codex 输出真实性

Codex 必须区分：

- 已执行并确认。
- 从代码中观察到。
- 从文档中确认。
- 推断。
- 尚未验证。

禁止：

- 没跑测试却说测试通过。
- 没查看工作区却说没有用户改动。
- 没验证真实 Provider 却说 Provider 已兼容。
- 没做故障注入却说恢复可靠。
- 把计划写成已实现结果。

如果工具受限，应明确说明无法验证的部分和残余风险。

---

## 11. 固定完成汇报

### 11.1 RESEARCH

```text
研究问题：
已验证事实：
推断：
未知项：
候选方案：
建议实验：
是否允许进入 DESIGN：
```

### 11.2 DESIGN

```text
ADR：
候选方案：
推荐：
迁移影响：
失败语义：
安全边界：
建议 Slice：
等待批准：
```

### 11.3 IMPLEMENT

```text
切片：
结果：
修改文件：
关键不变量：
目标测试：
相关测试：
全量回归：
未完成：
风险：
建议下一切片：
```

### 11.4 REVIEW

先给 Findings，按严重级别排序；没有阻断问题时明确说明。

### 11.5 INCIDENT

```text
症状：
复现：
分类：
根因：
最小修复：
验证：
数据/副作用影响：
预防措施：
```

---

## 12. 可直接使用的提示模板

### 12.1 开始研究

```text
模式：RESEARCH

研究 RNNN：[研究问题]

严格读取 .agents 下三份指导文档。
本轮不修改生产代码。

请：
1. 给出 KoawaAgent 当前行为的代码证据。
2. 只使用官方文档、官方源码和测试作为主要外部证据。
3. 区分事实、推断和未知项。
4. 至少提出两个候选方案。
5. 设计最小实验和故障注入。
6. 输出研究记录草案。

完成研究草案后停止，不进入实现。
```

### 12.2 开始设计

```text
模式：DESIGN

基于已完成的 RNNN，起草 ADR-NNN。
本轮不修改生产代码。

必须覆盖：
- 候选方案。
- 数据和 API 兼容。
- 失败与 Resume 语义。
- 并发。
- 权限和副作用。
- 测试计划。
- 回滚方案。

输出 ADR 后停止，等待人工批准。
```

### 12.3 执行切片

```text
模式：IMPLEMENT

只执行 [里程碑-切片编号]。
对应研究：[RNNN]
批准 ADR：[ADR-NNN]

开始前检查工作区并汇报范围。
不得跨切片；生产文件超过 8 个先停止拆分。
完成目标测试、相关测试、mvn test 和 git diff --check。
更新 Development Notes。
完成后按固定格式汇报并停止。
```

### 12.4 严格审查

```text
模式：REVIEW

只审查 [基准 commit] 到当前工作区的差异，不修复。

重点：
- 是否违反 Research/ADR。
- 是否跨 Slice。
- Checkpoint 和恢复边界。
- 工具副作用。
- Path/Command/Network 权限。
- Snapshot/API 兼容。
- revision 并发。
- 失败测试。

按 P0-P3 输出文件、行号、触发条件和实际后果。
```

### 12.5 故障处理

```text
模式：INCIDENT

停止新功能。
保留工作区和失败现场。

先复现并判断：
- 本 Slice 回归。
- 既有缺陷。
- 环境问题。
- 数据兼容问题。

只做最小修复，运行原失败测试、相关测试和全量回归。
失败关闭后停止，不开始下一 Slice。
```

---

## 13. 当前项目的默认下一步

在没有用户重新指定前：

1. 研究模式优先执行 `R003：Checkpoint 与工具副作用`。
2. 实施模式优先完成 Execution Plan 的 `M0` Resume 闭环。
3. 不提前开始 Workspace、Shell、LSP、Subagent 或 A2A。
4. 不把功能对齐 OpenCode 当作跳过研究的理由。

KoawaAgent 的目标不是最快拥有最多功能，而是让每个关键 Harness 机制都有证据、实验和清晰取舍。

---

## 14. 文档修订流程（2026-07-26 新增）

治理文档本身可能出错或过时。当编码代理发现文档之间冲突、文档与代码断裂或
引用失效时，不再只能停止，按以下流程处理：

```text
发现问题
  → 停止当前依赖该规则的动作
  → 提出文档修订 diff（引用具体章节与证据）
  → 用户批准
  → 更新文档 + 在 .agents/CHANGELOG.md 追加一行修订记录
  → 继续原任务
```

规则：

- 文档修订本身不算跨切片，可以与当前任务同轮完成，但必须单独提交。
- 修订必须保留原意图可追溯：CHANGELOG 记录改了什么、为什么。
- 未经用户批准，编码代理不得修改三份治理文档的规则性内容；
  纯粹的路径修正和错别字可以直接提出并在同轮批准后执行。
- 三份文档的版本号在每次批准修订后递增。
