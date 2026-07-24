# KoawaAgent

独立的 Java Agent 后端，包含确定性 Agent Loop、LLM Planner、MCP 工具调用、执行策略、恢复、超时、取消和运行结果协议。

本仓库不包含 RAG、知识库、向量检索、文档导入和前端。需要知识能力时，通过 MCP 工具调用独立的 RAG 服务。

## 核心边界

KoawaAgent 只保留一条最小但完整的运行链路：

```text
HTTP 请求
  → AgentState
  → LLM Planner
  → CALL_MCP_TOOL / ASK_CLARIFICATION / FINAL_ANSWER
  → Action Handler
  → Observation
  → AgentLoop 推进或停止
```

核心模块包括状态与步骤模型、Planner、Handler 路由、MCP 客户端、执行策略、失败恢复、超时取消、事件记录、会话历史和 LLM 适配。

以下能力明确不放入本仓库：内置 RAG、知识库业务、多 Agent、图工作流、Skills/A2A、可视化编排和通用沙箱平台。测试使用的固定脚本 Planner 也只存在于测试源集中。

完整的架构取舍、目标模型、阶段路线和验收标准见：
[KoawaAgent Runtime 设计与实施规划](docs/KoawaAgent-Runtime-Plan.md)。

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

当前会话历史使用进程内存保存，适合第一版验证。生产部署前应实现持久化的 `AgentConversationHistoryLoader`。

## Docker

```bash
SILICONFLOW_API_KEY=your-key docker compose up --build
```

MCP Server 可独立部署，通过 `agent.mcp.servers` 配置连接；KoawaAgent 不携带 RAG 服务和向量数据库。
