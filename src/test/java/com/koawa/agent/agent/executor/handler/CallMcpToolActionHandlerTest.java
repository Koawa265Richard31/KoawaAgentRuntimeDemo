package com.koawa.agent.agent.executor.handler;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.executor.policy.AgentExecutionPolicy;
import com.koawa.agent.agent.executor.policy.ToolExecutionDecision;
import com.koawa.agent.agent.mcp.McpToolExecutor;
import com.koawa.agent.agent.mcp.McpToolRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


class CallMcpToolActionHandlerTest {
    private final McpToolRegistry registry = mock(McpToolRegistry.class);
    private final McpToolExecutor toolExecutor = mock(McpToolExecutor.class);

    @Test
    void shouldExecuteRegisteredToolAndBuildObservation() {


        when(registry.getExecutor("query-sales"))
                .thenReturn(Optional.of(toolExecutor));

        when(toolExecutor.execute(Map.of(
                "region","华东"
        ))).thenReturn(McpSchema.CallToolResult.builder()
                .content(List.of(
                        new McpSchema.TextContent("销售额下降 15%"),
                        new McpSchema.TextContent("A 产品下降明显")
                ))
                .isError(false)
                .build());

        CallMcpToolActionHandler handler =
                new CallMcpToolActionHandler(registry, AgentExecutionPolicy.ALLOW_ALL);

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .arguments(Map.of(
                        "toolId", "query-sales",
                        "params", Map.of(
                                "region","华东"
                        )
                ))
                .build();

        AgentObservation result = handler.execute(action, AgentState.builder().build());

        assertTrue(result.isSuccess());
        assertEquals(
                "销售额下降 15%\nA 产品下降明显",
                result.getContent()
        );
        assertEquals(
                "query-sales",
                result.getMetadata().get("toolId")
        );
        assertNull(result.getErrorMessage());

        verify(toolExecutor).execute(Map.of(
                "region","华东"
        ));
    }

    @Test
    void shouldReturnFailedObservationWhenToolDoesNotExist() {
        when(registry.getExecutor("missing-tool"))
                .thenReturn(Optional.empty());

        CallMcpToolActionHandler handler =
                new CallMcpToolActionHandler(registry, AgentExecutionPolicy.ALLOW_ALL);

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .arguments(Map.of(
                        "toolId", "missing-tool",
                        "params", Map.of()
                ))
                .build();

        AgentObservation result = handler.execute(action, AgentState.builder().build());

        assertFalse(result.isSuccess());
        assertEquals("", result.getContent());
        assertEquals(
                "MCP tool not found: missing-tool",
                result.getErrorMessage()
        );

    }

    @Test
    void shouldNotExecuteToolWhenPolicyDeniesCall() {
        when(registry.getExecutor("query-sales"))
                .thenReturn(Optional.of(toolExecutor));

        AgentExecutionPolicy policy = (preparedCall, state) ->
                ToolExecutionDecision.deny("Tool is not allowed");
        CallMcpToolActionHandler handler =
                new CallMcpToolActionHandler(registry, policy);

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .arguments(Map.of(
                        "toolId", "query-sales",
                        "params", Map.of()
                ))
                .build();

        AgentObservation result = handler.execute(
                action,
                AgentState.builder().build()
        );

        assertFalse(result.isSuccess());
        assertEquals("", result.getContent());
        assertEquals(
                "MCP tool execution denied by policy: Tool is not allowed",
                result.getErrorMessage()
        );
        verifyNoInteractions(toolExecutor);
    }

    @Test
    void shouldReturnFailedObservationWhenToolReturnsError() {
        when(registry.getExecutor("query-sales"))
                .thenReturn(Optional.of(toolExecutor));

        when(toolExecutor.execute(Map.of()))
                .thenReturn(McpSchema.CallToolResult.builder()
                        .isError(true)
                        .content(List.of(
                                new McpSchema.TextContent("远程服务超时")
                        ))
                        .build()
                );

        CallMcpToolActionHandler handler =
                new CallMcpToolActionHandler(registry, AgentExecutionPolicy.ALLOW_ALL);

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .arguments(Map.of(
                        "toolId", "query-sales",
                        "params", Map.of()
                ))
                .build();

        AgentObservation result = handler.execute(
                action,
                AgentState.builder().build()
        );

        assertFalse(result.isSuccess());
        assertEquals("远程服务超时", result.getContent());
        assertEquals(
                "远程服务超时",
                result.getErrorMessage()
        );
    }
}
