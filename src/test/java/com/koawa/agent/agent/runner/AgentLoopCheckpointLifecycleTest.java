package com.koawa.agent.agent.runner;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.event.AgentEventSink;
import com.koawa.agent.agent.exception.AgentCheckpointLifecycleException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;
import com.koawa.agent.agent.recovery.AgentRecoveryDecision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentLoopCheckpointLifecycleTest {

    private static final Instant NOW =
            Instant.parse("2026-07-24T06:00:00Z");

    @Test
    void shouldCheckpointOnlyAfterStepIsCommitted() {
        AtomicInteger checkpointCount = new AtomicInteger();
        AtomicInteger checkpointStep = new AtomicInteger(-1);
        AtomicInteger checkpointSize = new AtomicInteger(-1);

        AgentCheckpointLifecycle lifecycle =
                new AgentCheckpointLifecycle() {
                    @Override
                    public void stepCommitted(AgentState state) {
                        checkpointCount.incrementAndGet();
                        checkpointStep.set(state.getCurrentStep());
                        checkpointSize.set(state.getSteps().size());
                    }
                };

        AgentState result = runner(lifecycle).run(state());

        assertEquals(1, checkpointCount.get());
        assertEquals(1, checkpointStep.get());
        assertEquals(1, checkpointSize.get());
        assertEquals(1, result.getCurrentStep());
    }

    @Test
    void shouldPropagateCheckpointFailureWithoutConvertingItToAgentError() {
        AgentCheckpointLifecycle lifecycle =
                new AgentCheckpointLifecycle() {
                    @Override
                    public void stepCommitted(AgentState state) {
                        throw new AgentCheckpointLifecycleException(
                                "checkpoint conflict",
                                new IllegalStateException("conflict"));
                    }
                };
        AgentState state = state();

        assertThrows(
                AgentCheckpointLifecycleException.class,
                () -> runner(lifecycle).run(state));
        assertNull(state.getStopReason());
    }

    @Test
    void shouldPropagateLeaseLostWithoutConvertingItToAgentError() {
        AgentCheckpointLifecycle lifecycle =
                new AgentCheckpointLifecycle() {
                    @Override
                    public void stepCommitted(AgentState state) {
                        throw new AgentExecutionLeaseLostException(
                                state.getTaskId(),
                                7,
                                Reason.OWNER_OR_TOKEN_MISMATCH
                        );
                    }
                };
        AgentState state = state();

        assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> runner(lifecycle).run(state)
        );
        assertNull(state.getStopReason());
    }

    private AgentLoopRunner runner(
            AgentCheckpointLifecycle checkpointLifecycle
    ) {
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .thought("finish")
                .build();
        AgentObservation observation = AgentObservation.builder()
                .actionType(AgentActionType.FINAL_ANSWER)
                .content("answer")
                .success(true)
                .build();

        return new AgentLoopRunner(
                state -> action,
                (plannedAction, state) -> observation,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                failureType -> AgentRecoveryDecision.STOP,
                Clock.fixed(NOW, ZoneOffset.UTC),
                checkpointLifecycle);
    }

    private AgentState state() {
        return AgentState.builder()
                .conversationId("conversation-1")
                .taskId("task-1")
                .originalQuestion("question")
                .currentStep(0)
                .maxSteps(2)
                .deadlineAt(NOW.plusSeconds(60))
                .steps(new ArrayList<>())
                .build();
    }
}
