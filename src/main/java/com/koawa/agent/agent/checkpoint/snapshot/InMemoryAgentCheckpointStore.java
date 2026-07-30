package com.koawa.agent.agent.checkpoint.snapshot;

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

        snapshots.compute(snapshot.taskId(), (taskId, current) -> {
            CheckpointWriteValidator.validate(
                    snapshot,
                    expectedRevision,
                    current);
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

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
