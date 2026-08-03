package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.resume.*;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentConversationTurnInput;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.MessageSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentInterruptConsumptionException;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import com.koawa.agent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentInterruptConsumptionServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-29T08:00:00Z");
    private static final Instant CONSUMED_AT =
            CREATED_AT.plusSeconds(60);

    private final InMemoryAgentCheckpointStore store =
            new InMemoryAgentCheckpointStore();
    private final AgentTaskSnapshotMapper mapper =
            new AgentTaskSnapshotMapper();
    private final AgentInterruptConsumptionService service =
            new AgentInterruptConsumptionService(
                    store,
                    mapper,
                    Clock.fixed(CONSUMED_AT, ZoneOffset.UTC)
            );

    @Test
    void shouldConsumeInputAndPersistItForRestartRecovery() {
        AgentTaskSnapshot waiting = waitingSnapshot("input-task");
        save(waiting);

        AgentInterruptConsumptionResult consumed = service.consume(
                command("input-task", "repository-a")
        );

        assertEquals("interrupt-1", consumed.interruptId());
        assertEquals(1, consumed.snapshot().revision());
        assertEquals(
                AgentTaskStatus.RUNNING,
                consumed.snapshot().status()
        );
        assertNull(consumed.snapshot().pendingInterrupt());
        assertEquals(waiting.steps(), consumed.snapshot().steps());
        assertEquals(waiting.nextStep(), consumed.snapshot().nextStep());
        assertEquals(
                List.of(
                        new MessageSnapshot(
                                ChatMessage.Role.USER,
                                "earlier context"
                        ),
                        new MessageSnapshot(
                                ChatMessage.Role.USER,
                                "repository-a"
                        )
                ),
                consumed.snapshot().historySnapshot()
        );
        assertFalse(consumed.snapshot().recoveryContext()
                .containsKey("stopReason"));
        assertFalse(consumed.snapshot().recoveryContext()
                .containsKey("finalAnswer"));
        assertEquals(
                "0",
                consumed.snapshot().recoveryContext().get(
                        "consumedUserInputStep"
                )
        );
        assertEquals(0, consumed.state().getConsumedUserInputStep());
        assertEquals(
                AgentConversationTurnInput.interruptReply(
                        "repository-a",
                        "interrupt-1"
                ),
                consumed.state().getCurrentTurnInput()
        );
        assertNull(consumed.state().getStopReason());
        assertNull(consumed.state().getFinalAnswer());

        AgentSnapshotRecoveryResult restarted =
                new AgentSnapshotRecoveryService(store, mapper)
                        .restore("input-task", 1);
        assertTrue(restarted.shouldContinue());
        assertEquals(
                "repository-a",
                restarted.state().getHistorySnapshot()
                        .get(1)
                        .getContent()
        );
        assertEquals(
                consumed.state().getCurrentTurnInput(),
                restarted.state().getCurrentTurnInput()
        );
    }

    @Test
    void shouldRejectWrongInterruptWithoutAdvancingCheckpoint() {
        AgentTaskSnapshot waiting = waitingSnapshot("wrong-id-task");
        save(waiting);

        AgentInterruptConsumptionException exception = assertThrows(
                AgentInterruptConsumptionException.class,
                () -> service.consume(new AgentResumeCommand(
                        "wrong-id-task",
                        0,
                        "interrupt-other",
                        "repository-a"
                ))
        );

        assertEquals(
                AgentInterruptConsumptionException.Reason
                        .INTERRUPT_ID_MISMATCH,
                exception.getReason()
        );
        assertEquals(waiting, store.load("wrong-id-task").orElseThrow());
    }

    @Test
    void shouldReplaceUnknownLegacyInputWhenConsumingNextInterrupt() {
        save(waitingSnapshot(
                "legacy-waiting-task",
                Map.of("consumedUserInputStep", "0")
        ));

        AgentInterruptConsumptionResult consumed = service.consume(
                command("legacy-waiting-task", "repository-b")
        );

        assertEquals(
                AgentConversationTurnInput.interruptReply(
                        "repository-b",
                        "interrupt-1"
                ),
                consumed.state().getCurrentTurnInput()
        );
    }

    @Test
    void shouldRejectMissingUserInputWithoutAdvancingCheckpoint() {
        AgentTaskSnapshot waiting = waitingSnapshot("missing-input-task");
        save(waiting);

        AgentInterruptConsumptionException exception = assertThrows(
                AgentInterruptConsumptionException.class,
                () -> service.consume(new AgentResumeCommand(
                        "missing-input-task",
                        0,
                        "interrupt-1"
                ))
        );

        assertEquals(
                AgentInterruptConsumptionException.Reason
                        .USER_INPUT_REQUIRED,
                exception.getReason()
        );
        assertEquals(
                waiting,
                store.load("missing-input-task").orElseThrow()
        );
    }

    @Test
    void shouldRejectDuplicateSubmissionAsRevisionConflict() {
        save(waitingSnapshot("duplicate-task"));
        AgentResumeCommand command =
                command("duplicate-task", "repository-a");
        service.consume(command);

        CheckpointConflictException conflict = assertThrows(
                CheckpointConflictException.class,
                () -> service.consume(command)
        );

        assertEquals(0, conflict.getExpectedRevision());
        assertEquals(1L, conflict.getActualRevision());
        assertEquals(
                2,
                store.load("duplicate-task")
                        .orElseThrow()
                        .historySnapshot()
                        .size()
        );
    }

    @Test
    void shouldRejectNonWaitingAndMissingTasks() {
        AgentTaskSnapshot running = runningSnapshot("running-task");
        save(running);

        AgentInterruptConsumptionException exception = assertThrows(
                AgentInterruptConsumptionException.class,
                () -> service.consume(command(
                        "running-task",
                        "repository-a"
                ))
        );
        assertEquals(
                AgentInterruptConsumptionException.Reason
                        .NOT_WAITING_FOR_INPUT,
                exception.getReason()
        );
        assertEquals(running, store.load("running-task").orElseThrow());

        assertThrows(
                CheckpointNotFoundException.class,
                () -> service.consume(command(
                        "missing-task",
                        "repository-a"
                ))
        );
    }

    private AgentResumeCommand command(
            String taskId,
            String userInput
    ) {
        return new AgentResumeCommand(
                taskId,
                0,
                "interrupt-1",
                userInput
        );
    }

    private void save(AgentTaskSnapshot snapshot) {
        store.save(snapshot, AgentCheckpointStore.NO_REVISION);
    }

    private AgentTaskSnapshot waitingSnapshot(String taskId) {
        return waitingSnapshot(taskId, Map.of());
    }

    private AgentTaskSnapshot waitingSnapshot(
            String taskId,
            Map<String, String> additionalRecoveryContext
    ) {
        Map<String, String> recoveryContext = new LinkedHashMap<>(
                Map.of(
                        "planningRecoveryAttempts",
                        "0",
                        "stopReason",
                        AgentStopReason.ASK_CLARIFICATION.name(),
                        "finalAnswer",
                        "Which repository?"
                )
        );
        recoveryContext.putAll(additionalRecoveryContext);
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                taskId,
                "conversation-1",
                "user-1",
                0,
                AgentTaskStatus.WAITING_FOR_INPUT,
                "resume task",
                1,
                4,
                CREATED_AT.plusSeconds(300),
                List.of(clarificationStep()),
                List.of(new MessageSnapshot(
                        ChatMessage.Role.USER,
                        "earlier context"
                )),
                recoveryContext,
                new PendingInterrupt(
                        "interrupt-1",
                        InterruptType.USER_INPUT,
                        "Which repository?",
                        Map.of(),
                        CREATED_AT
                ),
                CREATED_AT,
                CREATED_AT
        );
    }

    private AgentTaskSnapshot runningSnapshot(String taskId) {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                taskId,
                "conversation-1",
                "user-1",
                0,
                AgentTaskStatus.RUNNING,
                "resume task",
                0,
                4,
                CREATED_AT.plusSeconds(300),
                List.of(),
                List.of(),
                Map.of(),
                null,
                CREATED_AT,
                CREATED_AT
        );
    }

    private StepSnapshot clarificationStep() {
        return new StepSnapshot(
                0,
                AgentActionType.ASK_CLARIFICATION,
                "need repository",
                "{}",
                "Which repository?",
                "{}",
                true,
                null
        );
    }
}
