package com.koawa.agent.agent.executor;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutingAgentActionExecutorTest {

    @Test
    void shouldRouteActionToMatchingHandler() {
        AgentActionHandler handler = new AgentActionHandler() {
            @Override
            public AgentActionType supportedAction() {
                return AgentActionType.CALL_MCP_TOOL;
            }

            @Override
            public AgentObservation execute(AgentAction action, AgentState state) {
                return AgentObservation.builder()
                        .actionType(action.getType())
                        .content("tool result")
                        .success(true)
                        .build();
            }
        };

        RoutingAgentActionExecutor executor = new RoutingAgentActionExecutor(List.of(handler));

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .thought("call tool")
                .build();

        AgentObservation result = executor.execute(
                action,
                AgentState.builder().build()
        );

        assertTrue(result.isSuccess());
        assertEquals("tool result", result.getContent());
    }

    @Test
    void shouldRejectActionWithoutHandler() {
        RoutingAgentActionExecutor executor = new RoutingAgentActionExecutor(List.of());

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .build();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(
                        action,
                        AgentState.builder().build()
                )
        );

        assertEquals(
                "No handler for action type: CALL_MCP_TOOL",
                exception.getMessage()
        );
    }
}
