package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryResult;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.event.AgentEventSink;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import com.koawa.agent.agent.recovery.AgentRecoveryDecision;
import com.koawa.agent.agent.runner.AgentCancellationChecker;
import com.koawa.agent.agent.runner.AgentLoopRunner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSnapshotRecoveryServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-29T08:00:00Z");
    private static final Instant RECOVERED_AT =
            CREATED_AT.plusSeconds(120);

    private final InMemoryAgentCheckpointStore store =
            new InMemoryAgentCheckpointStore();
    private final AgentTaskSnapshotMapper mapper =
            new AgentTaskSnapshotMapper();
    private final AgentSnapshotRecoveryService service =
            new AgentSnapshotRecoveryService(
                    store,
                    mapper,
                    Clock.fixed(RECOVERED_AT, ZoneOffset.UTC),
                    () -> "interrupt-recovered"
            );

    @Test
    void shouldContinueAtNextStepWithoutReplayingCommittedToolHandler() {
        List<StepSnapshot> firstStep = List.of(step(
                0,
                AgentActionType.CALL_MCP_TOOL,
                "first result"));
        List<StepSnapshot> twoSteps = List.of(
                firstStep.get(0),
                step(
                        1,
                        AgentActionType.CALL_MCP_TOOL,
                        "second result")
        );
        save(snapshot(
                "continue-task",
                0,
                AgentTaskStatus.RUNNING,
                firstStep,
                Map.of(),
                CREATED_AT));
        store.save(
                snapshot(
                        "continue-task",
                        1,
                        AgentTaskStatus.RUNNING,
                        twoSteps,
                        Map.of(),
                        CREATED_AT.plusSeconds(60)),
                0
        );

        AgentSnapshotRecoveryResult recovered =
                service.restore("continue-task", 1);

        assertTrue(recovered.shouldContinue());
        assertEquals(
                AgentSnapshotRecoveryResult.Outcome.READY_TO_CONTINUE,
                recovered.outcome()
        );
        assertEquals(2, recovered.state().getCurrentStep());
        assertEquals(2, recovered.state().getSteps().size());
        assertEquals(1, store.load("continue-task")
                .orElseThrow().revision());

        AtomicInteger toolHandlerCalls = new AtomicInteger();
        AgentLoopRunner runner = new AgentLoopRunner(
                state -> {
                    assertEquals(2, state.getCurrentStep());
                    assertEquals(2, state.getSteps().size());
                    return AgentAction.builder()
                            .type(AgentActionType.FINAL_ANSWER)
                            .arguments(Map.of())
                            .build();
                },
                (action, state) -> {
                    if (action.getType()
                            == AgentActionType.CALL_MCP_TOOL) {
                        toolHandlerCalls.incrementAndGet();
                    }
                    return AgentObservation.builder()
                            .actionType(action.getType())
                            .content("done")
                            .metadata(Map.of())
                            .success(true)
                            .build();
                },
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                failureType -> AgentRecoveryDecision.STOP,
                Clock.fixed(RECOVERED_AT, ZoneOffset.UTC)
        );

        AgentState completed = runner.run(recovered.state());

        assertEquals(0, toolHandlerCalls.get());
        assertEquals(3, completed.getCurrentStep());
        assertEquals(AgentStopReason.FINAL_ANSWER, completed.getStopReason());
    }

    @Test
    void shouldRepairCommittedFinalAnswerWithOneTerminalRevision() {
        AgentTaskSnapshot running = snapshot(
                "final-task",
                0,
                AgentTaskStatus.RUNNING,
                List.of(step(
                        0,
                        AgentActionType.FINAL_ANSWER,
                        "final answer")),
                Map.of(),
                CREATED_AT
        );
        save(running);

        AgentSnapshotRecoveryResult recovered =
                service.restore("final-task", 0);

        assertFalse(recovered.shouldContinue());
        assertEquals(
                AgentSnapshotRecoveryResult.Outcome
                        .TERMINAL_STEP_REPAIRED,
                recovered.outcome()
        );
        assertEquals(1, recovered.snapshot().revision());
        assertEquals(
                AgentTaskStatus.COMPLETED,
                recovered.snapshot().status()
        );
        assertEquals(running.steps(), recovered.snapshot().steps());
        assertEquals(1, recovered.snapshot().nextStep());
        assertEquals(
                AgentStopReason.FINAL_ANSWER,
                recovered.state().getStopReason()
        );
        assertEquals(
                "final answer",
                recovered.state().getFinalAnswer()
        );

        AgentSnapshotRecoveryResult loadedAgain =
                service.restore("final-task", 1);
        assertEquals(
                AgentSnapshotRecoveryResult.Outcome.NOT_RUNNING,
                loadedAgain.outcome()
        );
        assertEquals(1, loadedAgain.snapshot().revision());
    }

    @Test
    void shouldRepairCommittedClarificationAsWaitingForInput() {
        save(snapshot(
                "clarification-task",
                0,
                AgentTaskStatus.RUNNING,
                List.of(step(
                        0,
                        AgentActionType.ASK_CLARIFICATION,
                        "Which repository?")),
                Map.of(),
                CREATED_AT));

        AgentSnapshotRecoveryResult recovered =
                service.restore("clarification-task", 0);

        assertEquals(
                AgentSnapshotRecoveryResult.Outcome
                        .TERMINAL_STEP_REPAIRED,
                recovered.outcome()
        );
        assertEquals(1, recovered.snapshot().revision());
        assertEquals(
                AgentTaskStatus.WAITING_FOR_INPUT,
                recovered.snapshot().status()
        );
        assertEquals(
                "interrupt-recovered",
                recovered.snapshot().pendingInterrupt().interruptId()
        );
        assertEquals(
                "Which repository?",
                recovered.snapshot().pendingInterrupt().prompt()
        );
        assertEquals(
                AgentStopReason.ASK_CLARIFICATION,
                recovered.state().getStopReason()
        );
    }

    @Test
    void shouldRepairNewClarificationAfterEarlierInputWasConsumed() {
        save(snapshot(
                "later-clarification-task",
                0,
                AgentTaskStatus.RUNNING,
                List.of(
                        step(
                                0,
                                AgentActionType.CALL_MCP_TOOL,
                                "first result"
                        ),
                        step(
                                1,
                                AgentActionType.ASK_CLARIFICATION,
                                "Which branch?"
                        )
                ),
                Map.of(
                        "consumedUserInputStep",
                        "0"
                ),
                CREATED_AT
        ));

        AgentSnapshotRecoveryResult recovered =
                service.restore("later-clarification-task", 0);

        assertEquals(
                AgentSnapshotRecoveryResult.Outcome
                        .TERMINAL_STEP_REPAIRED,
                recovered.outcome()
        );
        assertEquals(
                AgentTaskStatus.WAITING_FOR_INPUT,
                recovered.snapshot().status()
        );
        assertEquals(
                "Which branch?",
                recovered.snapshot().pendingInterrupt().prompt()
        );
    }

    @Test
    void shouldRejectStaleRevisionAndMissingTask() {
        save(snapshot(
                "revision-task",
                0,
                AgentTaskStatus.RUNNING,
                List.of(),
                Map.of(),
                CREATED_AT));

        CheckpointConflictException conflict = assertThrows(
                CheckpointConflictException.class,
                () -> service.restore("revision-task", 1)
        );
        assertEquals("revision-task", conflict.getTaskId());
        assertEquals(1, conflict.getExpectedRevision());
        assertEquals(0L, conflict.getActualRevision());

        assertThrows(
                CheckpointNotFoundException.class,
                () -> service.restore("missing-task", 0)
        );
    }

    private void save(AgentTaskSnapshot snapshot) {
        store.save(snapshot, AgentCheckpointStore.NO_REVISION);
    }

    private AgentTaskSnapshot snapshot(
            String taskId,
            long revision,
            AgentTaskStatus status,
            List<StepSnapshot> steps,
            Map<String, String> recoveryContext,
            Instant updatedAt
    ) {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                taskId,
                "conversation-1",
                "user-1",
                revision,
                status,
                "resume task",
                steps.size(),
                4,
                CREATED_AT.plusSeconds(300),
                steps,
                List.of(),
                recoveryContext,
                null,
                CREATED_AT,
                updatedAt
        );
    }

    private StepSnapshot step(
            int index,
            AgentActionType actionType,
            String observationContent
    ) {
        return new StepSnapshot(
                index,
                actionType,
                "step " + index,
                "{}",
                observationContent,
                "{}",
                true,
                null
        );
    }
}
