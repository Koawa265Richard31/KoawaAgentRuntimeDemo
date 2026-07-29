# KoawaAgent 编码代理入口

本仓库由三份治理文档约束所有编码代理（Codex、Claude Code 等）。开始任何工作前必须读取：

1. [.agents/KoawaAgent-Harness-Research-Protocol.md](.agents/KoawaAgent-Harness-Research-Protocol.md) —— 为什么研究、如何形成设计结论（E0–E5 证据等级）
2. [.agents/KoawaAgent-Coding-Harness-Codex-Execution-Plan.md](.agents/KoawaAgent-Coding-Harness-Codex-Execution-Plan.md) —— 做什么（M0–M11 里程碑与切片）
3. [.agents/KoawaAgent-Codex-Operating-Guide.md](.agents/KoawaAgent-Codex-Operating-Guide.md) —— 怎么工作（五种模式与停止条件）

三份文档中的"Codex"泛指任何编码代理。

指令优先级：用户当前明确指令 → 安全与权限要求 → Research Protocol → Execution Plan → Operating Guide → 现有项目习惯。

## 默认规则（详见 Operating Guide）

- 未明确模式时默认 `RESEARCH`，不得修改生产代码。
- 一轮只执行一个编号切片，不得跨切片；生产文件超过 8 个先停止拆分。
- 不删除或弱化测试；不覆盖用户未提交改动；仅在用户明确要求时本地提交；永不 push。
- revision/CAS、JSON、事务、并发 Resume 语义必须用 PostgreSQL/Testcontainers 验证，H2 结果不得宣称为 PostgreSQL 已验证。
- 汇报必须区分：已执行确认 / 代码观察 / 推断 / 尚未验证。

## 常用路径

- 开发记录：`docs/development/KoawaAgent-Development-Notes.md`
- 研究记录：`docs/research/RNNN-*.md`
- ADR：`docs/adr/`
- 治理文档修订记录：`.agents/CHANGELOG.md`
