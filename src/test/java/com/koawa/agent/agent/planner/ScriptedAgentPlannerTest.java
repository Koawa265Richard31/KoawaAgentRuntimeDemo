package com.koawa.agent.agent.planner;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptedAgentPlannerTest {

    @Test
    void shouldReturnActionForCurrentStep() {
        AgentAction toolAction = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .build();

        AgentAction finalAction = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .build();

        ScriptedAgentPlanner scriptedAgentPlanner = new ScriptedAgentPlanner(
                List.of(toolAction, finalAction)
        );

        AgentState agentState = AgentState.builder()
                .currentStep(0)
                .build();

        assertSame(toolAction, scriptedAgentPlanner.plan(agentState));

        agentState.setCurrentStep(1);

        assertSame(finalAction, scriptedAgentPlanner.plan(agentState));
    }

    @Test
    void shouldNotModifyCurrentStep() {
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .build();

        ScriptedAgentPlanner planner = new ScriptedAgentPlanner(List.of(action));

        AgentState state = AgentState.builder()
                .currentStep(0)
                .build();

        planner.plan(state);

        assertEquals(0, state.getCurrentStep());
    }

    @Test
    void shouldRejectStepBeyondScript() {
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .build();

        AgentState state = AgentState.builder()
                .currentStep(1)
                .build();

        ScriptedAgentPlanner planner = new ScriptedAgentPlanner(List.of(action));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> planner.plan(state)
        );

        assertEquals(
                "No scripted actions for step: 1",
                exception.getMessage()
        );
    }
}
