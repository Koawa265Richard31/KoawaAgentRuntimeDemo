# ADR-001：Checkpoint 仅保存最新 revision（单行快照）

## 状态

Accepted（追认既成实现，2026-07-26）

## 背景

`agent_checkpoint` 表以 `task_id` 为主键（`V1__create_agent_checkpoint.sql`），
每次 CAS 更新用新快照覆盖旧行。数据库中任一时刻只存在每个任务的最新 revision，
历史 revision 不可回放。

该设计在实现 JDBC Store 时形成，但当时未记录取舍。Execution Plan 同时声明了
"可审计"目标（§1.1 特色、M0-S5 出口"文档记录真实 revision 序列"），二者存在
张力，本 ADR 予以澄清。

## 决策驱动因素

- revision/CAS 只需要最新行即可实现乐观并发控制。
- 单行模型使 Store 实现和恢复语义最简单：load 即最新状态。
- 历史回放、审计与调试（比较 revision N-1 与 N）是真实需求，
  但其正确载体是 M5-S4 的 Tool Execution Ledger 与 M1-S2 的持久化 Trace Event，
  而不是整份 Snapshot 的每版留存。
- 每 revision 全量留存 Snapshot JSON 会带来写放大与清理策略负担。

## 候选方案

### 方案 A：单行最新快照（现状）

- 优点：CAS 简单、恢复语义直接、无清理负担。
- 缺点：revision 历史不可回放；崩溃取证只能依赖日志。

### 方案 B：append-only 快照表（task_id + revision 复合主键）

- 优点：完整历史、可回放、可取证。
- 缺点：写放大；需要"当前行"视图或查询约定；需要保留/清理策略。

### 方案 C：单行快照 + 独立事件/账本表（计划中的 M1-S2、M5-S4）

- 优点：状态恢复走最新快照，审计走细粒度事件与工具账本，各取所长。
- 缺点：依赖后续里程碑落地，在此之前审计能力有限。

## 决策

维持方案 A 作为当前实现，目标架构为方案 C：

1. `agent_checkpoint` 保持单行最新快照，不引入历史行。
2. "可审计"目标由 M1-S2（持久化 Trace Event）与 M5-S4（Tool Execution Ledger）
   承担；在两者落地前，不得宣称系统具备 revision 级审计能力。
3. M0-S5 出口的"文档记录真实 revision 序列"指通过测试与日志记录观察到的
   revision 变化，不要求数据库可回放。

## 后果

- 崩溃调试在 M1-S2 之前只能依赖日志与测试复现。
- M5-S4 设计 Ledger 时，如果发现事件粒度不足以审计，方案 B 可作为回退重估。

## 验证计划

- 现有 H2 与 PostgreSQL Store 测试覆盖单行 CAS 语义（E3）。
- M1-S2 落地时补充"事件序列可重建 revision 变化"的验证。

## 回滚方案

如需历史留存，新增 Flyway migration 建 append-only 表并双写过渡，
不修改已发布的 V1 migration。
