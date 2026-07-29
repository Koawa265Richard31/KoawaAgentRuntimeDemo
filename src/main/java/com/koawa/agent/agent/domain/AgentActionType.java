package com.koawa.agent.agent.domain;

public enum AgentActionType {
    /**
     * 调用 MCP 工具。
     */
    CALL_MCP_TOOL,
    /**
     * 信息不足，需要用户澄清。
     */
    ASK_CLARIFICATION,
    /**
     * 信息足够，输出最终回答。
     */
    FINAL_ANSWER;

    public boolean isTerminal() {
        return this == FINAL_ANSWER || this == ASK_CLARIFICATION;
    }
}
