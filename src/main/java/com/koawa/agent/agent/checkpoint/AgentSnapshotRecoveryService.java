package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Restores detached runtime state and repairs an incomplete terminal boundary.
 *
 * <p>The service never executes a planner or action handler. A non-terminal
 * RUNNING snapshot is returned at its persisted {@code nextStep}. When the
 * last committed step is terminal but the task is still RUNNING, only the
 * missing lifecycle revision is written.
 */
public final class AgentSnapshotRecoveryService {

    private final AgentCheckpointStore store;
    private final AgentTaskSnapshotMapper mapper;
    private final Clock clock;
    private final Supplier<String> interruptIdSupplier;

    public AgentSnapshotRecoveryService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper
    ) {
        this(
                store,
                mapper,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString()
        );
    }

    public AgentSnapshotRecoveryService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper,
            Clock clock,
            Supplier<String> interruptIdSupplier
    ) {
        this.store = Objects.requireNonNull(
                store,
                "store cannot be null"
        );
        this.mapper = Objects.requireNonNull(
                mapper,
                "mapper cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.interruptIdSupplier = Objects.requireNonNull(
                interruptIdSupplier,
                "interruptIdSupplier cannot be null"
        );
    }

    public AgentSnapshotRecoveryResult restore(
            String taskId,
            long expectedRevision
    ) {
        String actualTaskId = requireTaskId(taskId);
        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be negative"
            );
        }

        AgentTaskSnapshot snapshot = store.load(actualTaskId)
                .orElseThrow(() ->
                        new CheckpointNotFoundException(actualTaskId));
        requireExpectedRevision(snapshot, expectedRevision);

        AgentState state = mapper.toState(snapshot);
        if (snapshot.status() != AgentTaskStatus.RUNNING) {
            return new AgentSnapshotRecoveryResult(
                    snapshot,
                    state,
                    AgentSnapshotRecoveryResult.Outcome.NOT_RUNNING
            );
        }

        StepSnapshot lastStep = lastStep(snapshot);
        if (lastStep == null || !lastStep.actionType().isTerminal()) {
            return new AgentSnapshotRecoveryResult(
                    snapshot,
                    state,
                    AgentSnapshotRecoveryResult.Outcome.READY_TO_CONTINUE
            );
        }
        if (isConsumedClarificationBoundary(state, lastStep)) {
            return new AgentSnapshotRecoveryResult(
                    snapshot,
                    state,
                    AgentSnapshotRecoveryResult.Outcome.READY_TO_CONTINUE
            );
        }

        return repairTerminalStep(snapshot, state, lastStep);
    }

    private boolean isConsumedClarificationBoundary(
            AgentState state,
            StepSnapshot lastStep
    ) {
        Integer consumedStep = state.getConsumedUserInputStep();
        return lastStep.actionType() == AgentActionType.ASK_CLARIFICATION
                && consumedStep != null
                && consumedStep == lastStep.stepIndex();
    }

    private AgentSnapshotRecoveryResult repairTerminalStep(
            AgentTaskSnapshot current,
            AgentState state,
            StepSnapshot terminalStep
    ) {
        if (current.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "checkpoint revision limit reached for task "
                            + current.taskId()
            );
        }

        Instant updatedAt = monotonicUpdatedAt(current.updatedAt());
        AgentTaskStatus targetStatus;
        PendingInterrupt pendingInterrupt;

        switch (terminalStep.actionType()) {
            case FINAL_ANSWER -> {
                state.setStopReason(AgentStopReason.FINAL_ANSWER);
                state.setFinalAnswer(terminalStep.observationContent());
                targetStatus = AgentTaskStatus.COMPLETED;
                pendingInterrupt = null;
            }
            case ASK_CLARIFICATION -> {
                state.setStopReason(AgentStopReason.ASK_CLARIFICATION);
                state.setFinalAnswer(terminalStep.observationContent());
                targetStatus = AgentTaskStatus.WAITING_FOR_INPUT;
                pendingInterrupt = clarificationInterrupt(
                        terminalStep,
                        updatedAt
                );
            }
            default -> throw new IllegalStateException(
                    "unsupported terminal action "
                            + terminalStep.actionType()
            );
        }

        AgentTaskSnapshot repaired = mapper.toSnapshot(
                state,
                targetStatus,
                current.revision() + 1,
                pendingInterrupt,
                current.createdAt(),
                updatedAt
        );
        AgentTaskSnapshot saved = store.save(
                repaired,
                current.revision()
        );

        return new AgentSnapshotRecoveryResult(
                saved,
                state,
                AgentSnapshotRecoveryResult.Outcome
                        .TERMINAL_STEP_REPAIRED
        );
    }

    private PendingInterrupt clarificationInterrupt(
            StepSnapshot terminalStep,
            Instant createdAt
    ) {
        String prompt = terminalStep.observationContent();
        if (prompt == null || prompt.isBlank()) {
            prompt = "Additional user input is required";
        }
        return new PendingInterrupt(
                interruptIdSupplier.get(),
                InterruptType.USER_INPUT,
                prompt,
                Map.of(),
                createdAt
        );
    }

    private StepSnapshot lastStep(AgentTaskSnapshot snapshot) {
        if (snapshot.steps().isEmpty()) {
            return null;
        }
        return snapshot.steps().get(snapshot.steps().size() - 1);
    }

    private Instant monotonicUpdatedAt(Instant currentUpdatedAt) {
        Instant now = clock.instant();
        return now.isBefore(currentUpdatedAt)
                ? currentUpdatedAt
                : now;
    }

    private void requireExpectedRevision(
            AgentTaskSnapshot snapshot,
            long expectedRevision
    ) {
        if (snapshot.revision() != expectedRevision) {
            throw new CheckpointConflictException(
                    snapshot.taskId(),
                    expectedRevision,
                    snapshot.revision()
            );
        }
    }

    private String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException(
                    "taskId must be a non-blank string"
            );
        }
        return taskId.trim();
    }
}
