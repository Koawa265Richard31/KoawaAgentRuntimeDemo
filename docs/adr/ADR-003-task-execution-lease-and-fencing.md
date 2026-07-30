# ADR-003：任务执行权租约与 Fencing

## 状态

Accepted（2026-07-30，项目负责人批准进入 M0-S4 主线实现）

实施顺序调整：项目负责人明确要求暂不处理 Docker/Testcontainers 基础设施，因此
`M0-S4a` 延后，先实施 `M0-S4b`。这只调整实施顺序，不降低 `M0-S4c` 和 `M0-S4e`
对真实 PostgreSQL 并发语义的验收要求。

对应范围：

- `M0-S4`：执行权租约或 CAS Claim。
- `docs/research/R003-checkpoint-tool-side-effects.md`。
- `docs/adr/ADR-002-tool-side-effect-recovery.md`。

证据：

- E1：[LangGraph Agent Server](https://langchain-ai.github.io/langgraph/concepts/langgraph_server/)
  将 Checkpoint 与 Run Queue/Lease 分开，并声明同一 Thread 同时最多执行一个 Run。
- E1：[Kubernetes Lease API](https://kubernetes.io/docs/reference/kubernetes-api/coordination/lease-v1/)
  使用 holder、renew time、duration 和 transition counter 表达可续租执行权；
  [Coordinated Leader Election](https://kubernetes.io/docs/concepts/cluster-administration/coordinated-leader-election/)
  使用资源版本做乐观并发，租约过期后允许竞争接管。
- E1：[PostgreSQL Advisory Lock](https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS)
  的生命周期绑定 Session 或 Transaction，不直接提供跨连接的显式到期时间；
  [PostgreSQL Date/Time Functions](https://www.postgresql.org/docs/current/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT)
  区分事务时间、语句时间和真实时钟。
- E2：当前 `JdbcAgentCheckpointStore` 已使用 `task_id + revision` CAS，但没有执行权记录，
  `AgentResumeService` 只返回 `ACQUIRE_EXECUTION_CLAIM`，尚未真正 Claim。
- E2：当前 Snapshot 保存任务恢复状态；将心跳写入 Snapshot 会产生与业务进度无关的
  revision。

## 背景

两个请求可以同时读取同一个 `RUNNING` Snapshot，并且都通过
`AgentResumeService.evaluate()`：

```text
请求 A 读取 revision 7 → 允许申请执行权
请求 B 读取 revision 7 → 允许申请执行权
```

Checkpoint revision CAS 只能保证两次最终写入不会同时成功，不能阻止 A、B 在写入前都调用
模型或工具。若只增加一个永久 Claim，持有 Claim 的进程崩溃后任务又会永远卡住。

M0-S4 因此需要同时回答：

1. 谁可以推进同一个任务。
2. 进程崩溃后何时允许接管。
3. 旧执行者恢复运行后，为什么不能覆盖新执行者。
4. 执行权信息应放在哪里，是否污染 Snapshot。

## 决策驱动因素

- 同一时刻只能有一个数据库认可的有效执行者。
- Claim 必须在进程崩溃后自动失效。
- 过期 Claim 被接管后，旧执行者的迟到写入必须失败。
- 申请、续租、释放和接管必须使用 PostgreSQL 原子条件更新。
- Checkpoint revision 继续只表达任务业务进度，不能被心跳推进。
- M0 不引入 Redis、分布式调度平台或生产 Tool Ledger。
- 并发与过期语义必须由 PostgreSQL/Testcontainers 验证。
- 不把 Lease 宣称成任意外部副作用的 Exactly Once。

## 候选方案

### 方案 A：只依赖 Checkpoint revision CAS

优点是无需新表。缺点是冲突发生得太晚：两个执行者可能都已调用模型或工具，只在保存
Snapshot 时才发现其中一个输了。

### 方案 B：永久 CAS Claim

优点是状态少、实现简单。缺点是持有者进程崩溃后没有自动恢复路径，只能人工清理，不能
满足 M0-S4 的过期要求。

### 方案 C：PostgreSQL Advisory Lock

优点是 PostgreSQL 原生支持且竞争原子。缺点是 Session Lock 要为整个 Agent Run 长期占用
数据库连接，Transaction Lock 又会要求跨模型和工具调用持有长事务；两者都不直接表达
可观测、可配置的租约期限。

### 方案 D：把 Lease 字段加入 Snapshot

优点是少一张表。缺点是每次续租都会改 Snapshot revision，制造假的业务进度；恢复状态、
执行协调和序列化兼容也会被绑在一起。

### 方案 E：独立可续租 Lease + 单调 Fencing Token

优点是执行协调与恢复状态分离；支持崩溃过期；新持有者取得更大的 Token 后，可以拒绝旧
持有者的迟到写入。缺点是增加一张表、心跳和 Claim-aware 写入路径。

## 决策

选择方案 E：为每个任务维护一条独立、可续租的执行权记录，并为每次成功接管递增
`fencingToken`。

### 1. 职责边界

```text
Agent Snapshot
  负责：任务状态、Steps、nextStep、Pending Interrupt、业务 revision

Agent Execution Lease
  负责：当前持有者、fencing token、过期时间、续租和接管
```

Lease 不进入 `AgentTaskSnapshot`、`AgentState` 或 Snapshot JSON。心跳不得增加 Checkpoint
revision。

### 2. 持久化模型

新增独立表 `agent_execution_lease`：

| 字段 | 含义 |
|---|---|
| `task_id` | 一个任务只有一条 Lease 记录 |
| `owner_id` | 本次执行尝试的随机身份，不使用线程名或主机名代替 |
| `fencing_token` | 每次成功接管严格递增，初始值为 1 |
| `lease_expires_at` | 数据库判定的执行权失效时间 |
| `updated_at` | 最近一次申请、续租或释放时间 |

Lease 记录不会在正常释放时删除。释放只会把它原子地标记为已过期，从而保留 Token 历史；
下一次取得执行权时继续递增。Checkpoint 因保留策略被删除时，Lease 可以随任务一并清理。

应用层使用不可变值对象：

```text
AgentExecutionPermit
  taskId
  ownerId
  fencingToken
  expiresAt
```

调用方不能自行构造一个“更大 Token”来获得权限；只有 Store 成功执行 Claim 后才返回
Permit。

### 3. 原子操作语义

#### Acquire

申请必须同时满足：

- Checkpoint 存在。
- Checkpoint revision 等于调用方的 `expectedRevision`。
- 当前没有 Lease，或者已有 Lease 已过期。

成功时：

- 写入新的 `ownerId`。
- `fencingToken` 从 1 开始，之后每次接管加 1。
- 设置新的 `leaseExpiresAt`。
- 返回 `AgentExecutionPermit`。

两个请求用相同 task/revision 并发申请时只能有一个成功。失败方得到明确
`AgentExecutionConflictException`，不能进入 Agent Loop。

#### Renew

续租必须同时匹配 `taskId + ownerId + fencingToken`，并且旧 Lease 尚未过期。已经过期的
持有者不能通过续租“复活”；它只能重新竞争并取得更大的 Token。

#### Release

释放也必须匹配 `taskId + ownerId + fencingToken`。旧持有者的迟到 Release 不能释放新
持有者的 Lease。Release 是缩短有效期，不删除 Token 历史。

所有过期比较和新过期时间都由 PostgreSQL `statement_timestamp()` 计算，不使用 Worker
本地时间参与数据库所有权判断，避免多台机器时钟偏差。Lease 操作使用短事务，不跨模型或
工具调用持有数据库事务。

### 4. Fenced Checkpoint Write

仅在入口处成功 Acquire 仍然不够。以下时序中，A 会在暂停后恢复：

```text
A 取得 token 8 → A 长暂停 → Lease 过期
B 取得 token 9 → B 保存新进度
A 恢复 → A 尝试保存旧进度
```

因此 Resume 后的每一次 Checkpoint 写入必须在同一条数据库写语句中同时验证：

```text
checkpoint.revision == expectedRevision
lease.ownerId == permit.ownerId
lease.fencingToken == permit.fencingToken
lease.leaseExpiresAt > databaseNow
```

Token 或有效期不匹配时抛出 `AgentExecutionLeaseLostException`，旧执行者必须停止，不能把
它降级成普通 revision 冲突后自动重试。

当前首次创建 revision 0 的路径暂不要求 Lease；从 Resume 入口恢复执行的写入必须使用
Claim-aware API。M0-S5 接入 Resume 编排时，不允许绕回无 Permit 的保存路径。

### 5. 生命周期与心跳

Lease 时长和续租间隔配置化，建议首版默认：

```text
leaseDuration = 30 seconds
renewInterval = 10 seconds
```

约束：

- `renewInterval` 必须显著小于 `leaseDuration`。
- Resume 进入 Agent Loop 前启动每任务一个轻量心跳。
- 完成、失败或主动取消时尝试 Release；Release 失败不覆盖原业务结果。
- 进程崩溃时没有 Release，其他请求只能在 Lease 到期后接管。
- Renew 失败或 Lease Lost 时设置本地取消信号，最迟在下一个安全边界停止。
- 服务关闭时尽力 Release，但正确性不能依赖优雅关闭。

30/10 秒是可调整的初始运维值，不是领域常量。测试必须使用更短的测试配置或可控数据库
状态，不能让全量测试真实等待 30 秒。

### 6. Resume 顺序

`RUNNING` Snapshot：

```text
校验 taskId + expectedRevision
  → Acquire Lease
  → 恢复 State
  → 启动 Heartbeat
  → Agent Loop
  → Fenced Checkpoint Write
  → Release
```

`WAITING_FOR_INPUT` Snapshot：

```text
校验并原子消费 Interrupt
  → 得到新的 RUNNING revision
  → Acquire Lease
  → 恢复执行
```

Interrupt 消费和 Lease Acquire 之间崩溃时，Snapshot 已通过
`consumedUserInputStep` 表明输入被消费；重试从新的 RUNNING revision 申请 Lease，不会再次
生成同一 Interrupt。

### 7. 崩溃与副作用边界

Lease 能保证的是：

- 数据库在任一时刻只承认一个有效 Permit。
- 新持有者接管后，旧持有者不能再提交 Checkpoint。

Lease 不能保证的是：

- 已发出的 HTTP、Shell、Git、Patch 或 MCP 请求会被撤销。
- 发生长暂停时，旧进程与新进程在物理上绝不短暂重叠。
- 任意外部副作用 Exactly Once。

因此 ADR-002 的保守边界继续生效：没有 Tool Ledger、幂等键或权威结果查询时，不得仅凭
Lease 过期就宣称未知副作用可以安全重放。Fencing 保护内部状态，不撤销外部世界已经发生
的动作。

### 8. 冲突与安全边界

- Acquire 冲突对 M0-S5 映射为 HTTP 409。
- API 可以返回可重试提示和过期时间，但不得暴露内部 `ownerId`。
- Lease Lost 是当前执行者必须停止的安全错误，不自动转换成新 Claim。
- `ownerId` 是不可猜测的执行尝试 ID，但它不是认证凭据；API 权限仍由外层身份验证负责。
- 日志记录 `taskId + fencingToken + operation`，默认不输出完整 ownerId。

## 兼容与迁移影响

- 新增 Flyway V2 migration，不修改 V1 Checkpoint 表和 Snapshot JSON。
- 旧 Snapshot 可直接读取；第一次 Claim 时创建 Lease 行。
- `AgentCheckpointStore` 的普通创建/保存兼容路径暂时保留，Resume 路径新增 Permit-aware
  保存协议。
- M5-S4 Tool Ledger 落地后，Ledger Attempt 必须关联相同或派生自任务 Permit 的
  fencingToken。
- 不新增 Redis、消息队列或分布式调度服务。

## 失败语义

| 场景 | 结果 |
|---|---|
| 两个 Resume 同时申请 | 一个取得 Permit，另一个明确 Conflict |
| 持有者正常运行 | 周期 Renew，Fenced Write 成功 |
| 持有者进程崩溃 | 不再 Renew，到期后允许新 Token 接管 |
| 旧持有者在接管后恢复 | Renew、Release、Checkpoint Write 均被拒绝 |
| 数据库暂时不可用 | 不假定仍持有 Lease；停止在安全边界 |
| 外部调用完成但未记入 Checkpoint | 按 ADR-002 视为潜在未知结果，Lease 不负责去重 |

## 验证计划

### 领域与组件测试

- 首次 Acquire 返回 Token 1。
- 未过期时第二个 owner 明确冲突。
- 当前 owner 可以 Renew，其他 owner 或旧 Token 不能 Renew。
- Release 后重新 Acquire 返回更大的 Token。
- 过期后接管返回更大的 Token。
- 旧 Permit 不能 Release 新 Lease。
- Lease 心跳不修改 Snapshot revision。

### PostgreSQL/Testcontainers

- 两个独立连接并发 Acquire，同一 `taskId + expectedRevision` 只有一个成功。
- Claim 时 revision 已变化会失败，不能取得过时执行权。
- 不 Release 模拟进程崩溃，到期后可以接管。
- 接管后旧 Token 的 Checkpoint Update 影响行数为 0。
- 当前 Permit 的 revision CAS + Lease Fence 在同一写语句中成功。
- PostgreSQL 验证通过前，不宣称并发 Resume 语义已完成。

当前 Docker Engine 29 与项目 Testcontainers 1.21.3 存在 API 兼容问题。已通过临时
`api.version=1.44` 运行真实 PostgreSQL 测试；正式实现前应在独立兼容性切片升级到已修复
近期 Docker Engine 兼容性的 Testcontainers 版本，不能依赖开发者永久手传参数。

## 建议实施切片

ADR 批准后，将 M0-S4 拆为以下独立提交，避免单轮超过 8 个生产文件：

1. `M0-S4a`：修复 Testcontainers/Docker Engine 兼容性，证明普通 `mvn test` 能实际运行
   PostgreSQL 测试；不改业务语义。
2. `M0-S4b`：增加 Lease 领域协议、内存 Store 和确定性单元测试；不接 Agent Loop。
3. `M0-S4c`：增加 Flyway V2、JDBC Lease Store 和 PostgreSQL 并发/过期测试。
4. `M0-S4d`：接入 Permit-aware Checkpoint Write、心跳与 Lease Lost 停止语义。
5. `M0-S4e`：组合 Resume Claim 用例，验证两个并发 Resume 只有一个可进入执行阶段。

每个切片完成后更新开发记录并单独提交。M0-S5 再增加 REST Controller、409 映射和完整
Resume PostgreSQL E2E。

## 批准记录

本 ADR 已批准以下决策：

1. 使用独立 Lease 表，而不是把字段加入 Snapshot。
2. 使用可续租 Lease，而不是永久 CAS Claim 或长期数据库锁。
3. 使用单调 `fencingToken` 保护接管后的 Checkpoint 写入。
4. 以 30 秒 Lease、10 秒 Renew 作为可配置首版默认值。
5. 保持 `M0-S4a` 至 `M0-S4e` 的切片边界；当前先实施 `M0-S4b`，`M0-S4a` 延后。

## 回滚方案

- 在 Resume 接入前，可以移除应用层 Lease Bean 并保留未使用的 V2 表。
- 接入后回滚代码时不得重新开放无 Permit 的 Resume 写入；应暂时拒绝 Resume。
- Flyway migration 不执行破坏性 down migration。若方案停用，保留 Lease 行用于诊断，
  后续 migration 标记废弃。
- 回滚不能删除仍可能对应活跃 Worker 的执行权记录；先停止新 Resume 并等待最大 Lease
  期限。
