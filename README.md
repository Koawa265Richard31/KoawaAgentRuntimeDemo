# KoawaAgent

独立的 Java Agent 后端，包含确定性 Agent Loop、LLM Planner、MCP 工具调用、任务状态机、
PostgreSQL Checkpoint、Resume、执行租约、会话历史、超时、取消和运行结果协议。

本仓库不包含 RAG、知识库、向量检索、文档导入和前端。需要知识能力时，通过 MCP 工具调用独立的 RAG 服务。

## 核心边界

KoawaAgent 只保留一条最小但完整的运行链路：

```text
HTTP Chat / Resume 请求
  → Task + AgentState（revision 0）
  → PostgreSQL Checkpoint / 执行 Lease
  → LLM Planner
  → CALL_MCP_TOOL / ASK_CLARIFICATION / FINAL_ANSWER
  → Action Handler
  → Observation
  → Step Checkpoint
  → AgentLoop 推进、Interrupt 或停止
  → terminal Checkpoint + Conversation Turn 同事务提交
```

核心模块包括状态与步骤模型、Planner、Handler 路由、MCP 客户端、执行策略、失败恢复、超时取消、事件记录、会话历史和 LLM 适配。

当前 M0 范围不包含内置 RAG、知识库业务、多 Agent、图工作流、Skills/A2A、可视化编排和
通用沙箱平台；后续能力以执行规划中的里程碑为准。测试使用的固定脚本 Planner 只存在于测试源集中。

完整的架构取舍、目标模型、阶段路线和验收标准见：
[KoawaAgent Coding Harness 执行规划](.agents/KoawaAgent-Coding-Harness-Codex-Execution-Plan.md)。

编码代理（Codex、Claude Code 等）开始工作前必须先阅读 [AGENTS.md](AGENTS.md)。

## 本地运行

1. 启动 PostgreSQL：`docker compose up -d postgres`。
2. 设置环境变量 `SILICONFLOW_API_KEY`。
3. 根据需要启动 MCP Server，并在 `agent.mcp.servers` 中配置地址。
4. 执行 `mvn spring-boot:run`。

默认数据库连接：

```text
url      = jdbc:postgresql://localhost:5432/koawa_agent
username = koawa_agent
password = koawa_agent
```

可使用 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD` 覆盖。

同步聊天接口：

```http
POST /api/agent/v1/chat
Content-Type: application/json

{
  "question": "北京天气怎么样？",
  "conversationId": "optional-conversation-id",
  "taskId": "optional-task-id",
  "userId": "optional-user-id"
}
```

取消接口：

```http
POST /api/agent/v1/tasks/{taskId}/cancel
```

Task 查询接口：

```http
GET /api/agent/v1/tasks/{taskId}
GET /api/agent/v1/conversations/{conversationId}/tasks
```

澄清回复或运行恢复接口：

```http
POST /api/agent/v1/tasks/{taskId}/resume
Content-Type: application/json

{
  "expectedRevision": 2,
  "interruptId": "pending-interrupt-id",
  "userInput": "用户补充的信息"
}
```

成功执行或只修复 terminal boundary 返回 `200`；状态、revision、Interrupt 或执行权冲突返回
`409`。调用方应以最新 Task 查询结果作为权威状态。

## 持久化与恢复

- Task 使用 PostgreSQL latest-only Snapshot 和 revision CAS；创建即保存 `RUNNING revision 0`。
- Resume 在执行前获取可续租 Lease，Checkpoint 写入同时校验 revision 与 fencing token。
- `FINAL_ANSWER`/`ASK_CLARIFICATION` 的 terminal Checkpoint 与 Conversation Turn 在同一事务提交。
- 会话历史由 `JdbcAgentConversationStore` 持久化；数据库保留全部 Turn，当前模型上下文读取最近
  10 个 Turn。
- 真实 PostgreSQL E2E 已验证：terminal Step 保存后重启、终态修复、ASK → Resume → FINAL、
  再次重启后新 Task 加载旧历史，均不会重复生成 Turn。

当前已知边界：

- Lease 和 Checkpoint 不为模型、工具或 HTTP 响应提供分布式 Exactly Once。
- Task 的 `deadlineAt` 当前不会在等待用户输入期间暂停；超过原始 turn timeout 后 Resume 会超时。
- M0 Checkpoint/Resume 恢复闭环已经完成；Testcontainers 固定为兼容近期 Docker Engine 的
  `1.21.4`，标准测试命令不再依赖手传 Docker API 版本。
- 当前模型主路径仍是文本 JSON 单 `AgentAction`；M1 会升级为 Provider 原生 `ModelTurn` 协议。

## 测试

在 Docker Desktop 环境执行包含 PostgreSQL/Testcontainers 的完整回归：

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
mvn -q clean
mvn -q test
```

不需要设置 `api.version`。PostgreSQL 用例被跳过仍不能作为持久化与并发语义通过的证据；验收时
应先 clean 避免陈旧 Surefire XML 污染统计，并同时核对 Maven 退出码和 `skipped=0`。

## Docker

```bash
SILICONFLOW_API_KEY=your-key docker compose up --build
```

MCP Server 可独立部署，通过 `agent.mcp.servers` 配置连接；KoawaAgent 不携带 RAG 服务和向量数据库。
