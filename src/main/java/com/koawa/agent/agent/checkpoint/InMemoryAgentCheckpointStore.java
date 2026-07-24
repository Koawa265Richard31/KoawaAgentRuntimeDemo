package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentTaskSnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory checkpoint store for tests and local runtime use.
 */
public final class InMemoryAgentCheckpointStore
        implements AgentCheckpointStore {

    private static final Comparator<AgentTaskSnapshot> NEWEST_FIRST =
            Comparator.comparing(AgentTaskSnapshot::updatedAt)
                    .reversed()
                    .thenComparing(AgentTaskSnapshot::taskId);

    private final ConcurrentMap<String, AgentTaskSnapshot> snapshots =
            new ConcurrentHashMap<>();

    @Override
    public AgentTaskSnapshot save(
            AgentTaskSnapshot snapshot,
            long expectedRevision
    ) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (expectedRevision < NO_REVISION) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be less than " + NO_REVISION);
        }

        snapshots.compute(snapshot.taskId(), (taskId, current) -> {
            validateRevision(snapshot, expectedRevision, current);
            if (current != null) {
                validateUpdate(current, snapshot);
            }
            return snapshot;
        });
        return snapshot;
    }

    @Override
    public Optional<AgentTaskSnapshot> load(String taskId) {
        return Optional.ofNullable(snapshots.get(requireText(taskId, "taskId")));
    }

    @Override
    public List<AgentTaskSnapshot> list(String conversationId) {
        String actualConversationId = requireText(
                conversationId,
                "conversationId");
        return snapshots.values().stream()
                .filter(snapshot ->
                        snapshot.conversationId().equals(actualConversationId))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public void delete(String taskId) {
        snapshots.remove(requireText(taskId, "taskId"));
    }

    private void validateRevision(
            AgentTaskSnapshot next,
            long expectedRevision,
            AgentTaskSnapshot current
    ) {
        if (current == null) {
            if (expectedRevision != NO_REVISION) {
                throw conflict(next.taskId(), expectedRevision, null);
            }
            if (next.revision() != 0) {
                throw new IllegalArgumentException(
                        "a new checkpoint must start at revision 0");
            }
            return;
        }

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
    }

    private void validateUpdate(
            AgentTaskSnapshot current,
            AgentTaskSnapshot next
    ) {
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
    }

    private CheckpointConflictException conflict(
            String taskId,
            long expectedRevision,
            Long actualRevision
    ) {
        return new CheckpointConflictException(
                taskId,
                expectedRevision,
                actualRevision);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
