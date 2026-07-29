package com.koawa.agent.agent.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRunResultTest {

    @Test
    void shouldCreateRunSummaryFromState() {
        AgentState state = AgentState.builder()
                .conversationId("conversation-1")
                .taskId("task-1")
                .stopReason(AgentStopReason.ERROR)
                .steps(List.of(
                        AgentStep.builder().stepIndex(0).build(),
                        AgentStep.builder().stepIndex(1).build()
                ))
                .planningRecoveryAttempts(1)
                .failureType(AgentFailureType.MODEL_CALL_FAILED)
                .errorMessage("model unavailable")
                .build();

        AgentRunResult result = AgentRunResult.from(state);

        assertEquals("conversation-1", result.conversationId());
        assertEquals("task-1", result.taskId());
        assertEquals(AgentStopReason.ERROR, result.stopReason());
        assertEquals(2, result.stepCount());
        assertEquals(1, result.planningRecoveryAttempts());
        assertEquals(
                AgentFailureType.MODEL_CALL_FAILED,
                result.failureType()
        );
        assertNull(result.content());
        assertEquals("model unavailable", result.errorMessage());
    }

    @Test
    void shouldRejectNullState() {
        assertThrows(
                NullPointerException.class,
                () -> AgentRunResult.from(null)
        );
    }
}
