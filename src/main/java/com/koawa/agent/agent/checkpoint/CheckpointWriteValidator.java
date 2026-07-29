package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.exception.CheckpointConflictException;

import java.util.Objects;

final class CheckpointWriteValidator {

    private CheckpointWriteValidator() {
    }

    static void validate(
            AgentTaskSnapshot next,
            long expectedRevision,
            AgentTaskSnapshot current
    ) {
        Objects.requireNonNull(next, "snapshot cannot be null");
        if (expectedRevision < AgentCheckpointStore.NO_REVISION) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be less than "
                            + AgentCheckpointStore.NO_REVISION);
        }

        if (current == null) {
            validateCreate(next, expectedRevision);
            return;
        }
        validateUpdate(next, expectedRevision, current);
    }

    private static void validateCreate(
            AgentTaskSnapshot next,
            long expectedRevision
    ) {
        if (expectedRevision != AgentCheckpointStore.NO_REVISION) {
            throw conflict(next.taskId(), expectedRevision, null);
        }
        if (next.revision() != 0) {
            throw new IllegalArgumentException(
                    "a new checkpoint must start at revision 0");
        }
    }

    private static void validateUpdate(
            AgentTaskSnapshot next,
            long expectedRevision,
            AgentTaskSnapshot current
    ) {
        if (current.revision() != expectedRevision) {
            throw conflict(
                    next.taskId(),
                    expectedRevision,
                    current.revision());
        }
        if (expectedRevision == Long.MAX_VALUE
                || next.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException(
                    "an updated checkpoint must advance revision by exactly one");
        }
        if (!current.conversationId().equals(next.conversationId())
                || !Objects.equals(current.userId(), next.userId())
                || !current.originalQuestion().equals(next.originalQuestion())
                || !current.createdAt().equals(next.createdAt())) {
            throw new IllegalArgumentException(
                    "checkpoint task identity cannot change");
        }
        if (!current.status().canTransitionTo(next.status())) {
            throw new IllegalStateException(
                    "illegal checkpoint status transition: "
                            + current.status() + " -> " + next.status());
        }
        if (next.updatedAt().isBefore(current.updatedAt())) {
            throw new IllegalArgumentException(
                    "checkpoint updatedAt cannot move backwards");
        }
        if (next.schemaVersion() < current.schemaVersion()) {
            throw new IllegalArgumentException(
                    "checkpoint schemaVersion cannot move backwards");
        }
        if (next.maxSteps() != current.maxSteps()) {
            throw new IllegalArgumentException(
                    "checkpoint maxSteps cannot change");
        }
        if (next.nextStep() < current.nextStep()
                || !next.steps().subList(0, current.steps().size())
                .equals(current.steps())) {
            throw new IllegalArgumentException(
                    "checkpoint completed step history cannot be rewritten");
        }
    }

    private static CheckpointConflictException conflict(
            String taskId,
            long expectedRevision,
            Long actualRevision
    ) {
        return new CheckpointConflictException(
                taskId,
                expectedRevision,
                actualRevision);
    }
}
