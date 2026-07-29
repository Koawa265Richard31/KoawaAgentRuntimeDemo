package com.koawa.agent.agent.executor.policy;

import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.executor.tool.PreparedToolCall;
import com.koawa.agent.agent.mcp.McpToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AllowListAgentExecutionPolicyTest {

    private final McpToolExecutor executor = mock(McpToolExecutor.class);
    private final AgentState state = AgentState.builder().build();

    @Test
    void shouldAllowConfiguredTool() {
        AgentExecutionPolicy policy =
                new AllowListAgentExecutionPolicy(Set.of("sales_query"));

        ToolExecutionDecision decision = policy.evaluate(
                preparedCall("sales_query"),
                state
        );

        assertTrue(decision.allowed());
        assertNull(decision.reason());
    }

    @Test
    void shouldDenyToolOutsideAllowList() {
        AgentExecutionPolicy policy =
                new AllowListAgentExecutionPolicy(Set.of("sales_query"));

        ToolExecutionDecision decision = policy.evaluate(
                preparedCall("weather_query"),
                state
        );

        assertFalse(decision.allowed());
        assertEquals(
                "Tool is not in allowlist: weather_query",
                decision.reason()
        );
    }

    private PreparedToolCall preparedCall(String toolId) {
        return new PreparedToolCall(toolId, Map.of(), executor);
    }
}
