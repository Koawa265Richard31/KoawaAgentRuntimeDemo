package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.AgentResumeResult.NextAction;
import com.koawa.agent.agent.checkpoint.AgentResumeResult.RejectionReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResumeServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T08:00:00Z");

    private final InMemoryAgentCheckpointStore store =
            new InMemoryAgentCheckpointStore();
    private final AgentResumeService service =
            new AgentResumeService(store);

    @Test
    void shouldRequireExecutionClaimForRunningTask() {
        save(snapshot(
                "running-task",
                AgentTaskStatus.RUNNING,
                null));

        AgentResumeResult result = service.evaluate(
                new AgentResumeCommand(
                        "running-task",
                        0,
                        null));

        assertTrue(result.accepted());
        assertEquals(
                NextAction.ACQUIRE_EXECUTION_CLAIM,
                result.nextAction());
        assertEquals(RejectionReason.NONE, result.rejectionReason());
        assertEquals(AgentTaskStatus.RUNNING, result.currentStatus());
    }

    @Test
    void shouldRejectInterruptIdForRunningTask() {
        save(snapshot(
                "running-task",
                AgentTaskStatus.RUNNING,
                null));

        AgentResumeResult result = service.evaluate(
                new AgentResumeCommand(
                        "running-task",
                        0,
                        "stale-interrupt"));

        assertFalse(result.accepted());
        assertEquals(NextAction.REJECT, result.nextAction());
        assertEquals(
                RejectionReason.INTERRUPT_ID_NOT_APPLICABLE,
                result.rejectionReason());
    }

    @Test
    void shouldRequireMatchingUserInputInterrupt() {
        save(snapshot(
                "input-task",
                AgentTaskStatus.WAITING_FOR_INPUT,
                interrupt(
                        "interrupt-1",
                        InterruptType.USER_INPUT)));

        AgentResumeResult missing = service.evaluate(
                new AgentResumeCommand(
                        "input-task",
                        0,
                        null));
        AgentResumeResult mismatched = service.evaluate(
                new AgentResumeCommand(
                        "input-task",
                        0,
                        "interrupt-other"));
        AgentResumeResult matched = service.evaluate(
                new AgentResumeCommand(
                        "input-task",
                        0,
                        "interrupt-1"));

        assertEquals(
                RejectionReason.INTERRUPT_ID_REQUIRED,
                missing.rejectionReason());
        assertEquals(
                RejectionReason.INTERRUPT_ID_MISMATCH,
                mismatched.rejectionReason());
        assertTrue(matched.accepted());
        assertEquals(
                NextAction.CONSUME_USER_INPUT_INTERRUPT,
                matched.nextAction());
        assertEquals(
                RejectionReason.NONE,
                matched.rejectionReason());
    }

    @Test
    void shouldRejectApprovalResumeUntilApprovalSlice() {
        save(snapshot(
                "approval-task",
                AgentTaskStatus.WAITING_FOR_APPROVAL,
                interrupt(
                        "approval-1",
                        InterruptType.APPROVAL)));

        AgentResumeResult result = service.evaluate(
                new AgentResumeCommand(
                        "approval-task",
                        0,
                        "approval-1"));

        assertFalse(result.accepted());
        assertEquals(
                RejectionReason.APPROVAL_RESUME_NOT_SUPPORTED,
                result.rejectionReason());
    }

    @Test
    void shouldRejectEveryTerminalStatus() {
        for (AgentTaskStatus status : List.of(
                AgentTaskStatus.COMPLETED,
                AgentTaskStatus.FAILED,
                AgentTaskStatus.CANCELLED,
                AgentTaskStatus.TIMED_OUT)) {
            String taskId = "terminal-" + status.name();
            save(snapshot(taskId, status, null));

            AgentResumeResult result = service.evaluate(
                    new AgentResumeCommand(taskId, 0, null));

            assertFalse(result.accepted(), status.name());
            assertEquals(
                    RejectionReason.TERMINAL_STATUS,
                    result.rejectionReason(),
                    status.name());
        }
    }

    @Test
    void shouldValidateExpectedRevisionBeforeStatusMatrix() {
        save(snapshot(
                "completed-task",
                AgentTaskStatus.COMPLETED,
                null));

        CheckpointConflictException exception = assertThrows(
                CheckpointConflictException.class,
                () -> service.evaluate(new AgentResumeCommand(
                        "completed-task",
                        1,
                        null)));

        assertEquals("completed-task", exception.getTaskId());
        assertEquals(1, exception.getExpectedRevision());
        assertEquals(0L, exception.getActualRevision());
    }

    @Test
    void shouldRejectMissingTaskAndInvalidCommand() {
        assertThrows(
                CheckpointNotFoundException.class,
                () -> service.evaluate(new AgentResumeCommand(
                        "missing-task",
                        0,
                        null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentResumeCommand(" ", 0, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentResumeCommand("task-1", -1, null));

        AgentResumeCommand normalized =
                new AgentResumeCommand(
                        " task-1 ",
                        0,
                        " ");
        assertEquals("task-1", normalized.taskId());
        assertNull(normalized.interruptId());
    }

    private void save(AgentTaskSnapshot snapshot) {
        store.save(snapshot, AgentCheckpointStore.NO_REVISION);
    }

    private AgentTaskSnapshot snapshot(
            String taskId,
            AgentTaskStatus status,
            PendingInterrupt interrupt
    ) {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                taskId,
                "conversation-1",
                "user-1",
                0,
                status,
                "resume task",
                0,
                4,
                NOW.plusSeconds(300),
                List.of(),
                List.of(),
                Map.of(),
                interrupt,
                NOW,
                NOW
        );
    }

    private PendingInterrupt interrupt(
            String interruptId,
            InterruptType type
    ) {
        return new PendingInterrupt(
                interruptId,
                type,
                "continue?",
                Map.of(),
                NOW
        );
    }
}
