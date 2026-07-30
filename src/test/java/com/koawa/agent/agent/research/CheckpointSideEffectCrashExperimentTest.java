package com.koawa.agent.agent.research;

import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.event.AgentEventSink;
import com.koawa.agent.agent.recovery.AgentRecoveryDecision;
import com.koawa.agent.agent.runner.AgentCancellationChecker;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;
import com.koawa.agent.agent.exception.AgentCheckpointLifecycleException;
import com.koawa.agent.agent.runner.AgentLoopRunner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R003 experiment: characterizes the crash window between a successful
 * side effect and persistence of the completed step.
 */
class CheckpointSideEffectCrashExperimentTest {

    private static final Instant NOW =
            Instant.parse("2026-07-26T02:00:00Z");
    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void shouldDemonstrateDuplicateSideEffectWhenCrashPrecedesCheckpoint() {
        InMemoryAgentCheckpointStore store =
                new InMemoryAgentCheckpointStore();
        AgentCheckpointService checkpointService =
                new AgentCheckpointService(
                        store,
                        new AgentTaskSnapshotMapper(),
                        CLOCK);
        AgentState initialState = initialState();
        checkpointService.create(initialState);

        AtomicInteger externalSideEffectCount = new AtomicInteger();
        AgentCheckpointLifecycle failingCheckpoint =
                new AgentCheckpointLifecycle() {
                    @Override
                    public void stepCommitted(AgentState state) {
                        throw new AgentCheckpointLifecycleException(
                                "simulated crash before step persistence",
                                new IllegalStateException(
                                        "checkpoint unavailable"));
                    }
                };

        assertThrows(
                AgentCheckpointLifecycleException.class,
                () -> runner(
                        externalSideEffectCount,
                        failingCheckpoint).run(initialState));

        AgentCheckpointService.LoadedAgentCheckpoint loaded =
                checkpointService.load("task-r003").orElseThrow();
        assertEquals(0, loaded.snapshot().revision());
        assertEquals(0, loaded.state().getCurrentStep());
        assertEquals(0, loaded.state().getSteps().size());
        assertEquals(1, externalSideEffectCount.get());

        AgentState resumedState = loaded.state();
        runner(
                externalSideEffectCount,
                AgentCheckpointLifecycle.NOOP).run(resumedState);

        assertEquals(2, externalSideEffectCount.get());
        assertEquals(1, resumedState.getCurrentStep());
        assertEquals(1, resumedState.getSteps().size());
    }

    private AgentLoopRunner runner(
            AtomicInteger externalSideEffectCount,
            AgentCheckpointLifecycle checkpointLifecycle
    ) {
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .thought("perform external write")
                .build();

        return new AgentLoopRunner(
                state -> action,
                (plannedAction, state) -> {
                    int invocation =
                            externalSideEffectCount.incrementAndGet();
                    return AgentObservation.builder()
                            .actionType(AgentActionType.CALL_MCP_TOOL)
                            .content("external write " + invocation)
                            .success(true)
                            .build();
                },
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                failureType -> AgentRecoveryDecision.STOP,
                CLOCK,
                checkpointLifecycle);
    }

    private AgentState initialState() {
        return AgentState.builder()
                .conversationId("conversation-r003")
                .taskId("task-r003")
                .userId("researcher")
                .originalQuestion("perform an external write")
                .currentStep(0)
                .maxSteps(1)
                .deadlineAt(NOW.plusSeconds(60))
                .steps(new ArrayList<>())
                .build();
    }
}
