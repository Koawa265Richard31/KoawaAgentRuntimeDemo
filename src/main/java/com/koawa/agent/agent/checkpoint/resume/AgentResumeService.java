package com.koawa.agent.agent.checkpoint.resume;

import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;

import java.util.Objects;

/**
 * Read-only M0-S1 use case for validating Resume status and identity.
 *
 * <p>This service does not claim execution, consume interrupts, mutate a
 * checkpoint, or run the Agent loop.
 */
public final class AgentResumeService {

    private final AgentCheckpointStore store;

    public AgentResumeService(AgentCheckpointStore store) {
        this.store = Objects.requireNonNull(
                store,
                "store cannot be null"
        );
    }

    public AgentResumeResult evaluate(AgentResumeCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        AgentTaskSnapshot snapshot = store.load(command.taskId())
                .orElseThrow(() ->
                        new CheckpointNotFoundException(command.taskId()));
        requireExpectedRevision(command, snapshot);

        return switch (snapshot.status()) {
            case RUNNING -> evaluateRunning(command, snapshot);
            case WAITING_FOR_INPUT ->
                    evaluateWaitingForInput(command, snapshot);
            case WAITING_FOR_APPROVAL -> reject(
                    snapshot,
                    AgentResumeResult.RejectionReason
                            .APPROVAL_RESUME_NOT_SUPPORTED
            );
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT -> reject(
                    snapshot,
                    AgentResumeResult.RejectionReason.TERMINAL_STATUS
            );
        };
    }

    private AgentResumeResult evaluateRunning(
            AgentResumeCommand command,
            AgentTaskSnapshot snapshot
    ) {
        if (command.interruptId() != null) {
            return reject(
                    snapshot,
                    AgentResumeResult.RejectionReason
                            .INTERRUPT_ID_NOT_APPLICABLE
            );
        }
        return AgentResumeResult.proceedWith(
                snapshot.taskId(),
                snapshot.revision(),
                snapshot.status(),
                AgentResumeResult.NextAction.ACQUIRE_EXECUTION_CLAIM
        );
    }

    private AgentResumeResult evaluateWaitingForInput(
            AgentResumeCommand command,
            AgentTaskSnapshot snapshot
    ) {
        if (command.interruptId() == null) {
            return reject(
                    snapshot,
                    AgentResumeResult.RejectionReason
                            .INTERRUPT_ID_REQUIRED
            );
        }

        PendingInterrupt interrupt =
                Objects.requireNonNull(snapshot.pendingInterrupt());
        if (!interrupt.interruptId().equals(command.interruptId())) {
            return reject(
                    snapshot,
                    AgentResumeResult.RejectionReason
                            .INTERRUPT_ID_MISMATCH
            );
        }

        return AgentResumeResult.proceedWith(
                snapshot.taskId(),
                snapshot.revision(),
                snapshot.status(),
                AgentResumeResult.NextAction
                        .CONSUME_USER_INPUT_INTERRUPT
        );
    }

    private void requireExpectedRevision(
            AgentResumeCommand command,
            AgentTaskSnapshot snapshot
    ) {
        if (snapshot.revision() != command.expectedRevision()) {
            throw new CheckpointConflictException(
                    command.taskId(),
                    command.expectedRevision(),
                    snapshot.revision()
            );
        }
    }

    private AgentResumeResult reject(
            AgentTaskSnapshot snapshot,
            AgentResumeResult.RejectionReason reason
    ) {
        return AgentResumeResult.rejected(
                snapshot.taskId(),
                snapshot.revision(),
                snapshot.status(),
                reason
        );
    }
}
