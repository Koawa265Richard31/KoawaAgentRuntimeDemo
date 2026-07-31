package com.koawa.agent.agent.checkpoint.query;

import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only application service for task control-plane queries.
 */
public final class AgentTaskQueryService {

    private final AgentCheckpointStore store;

    public AgentTaskQueryService(AgentCheckpointStore store) {
        this.store = Objects.requireNonNull(
                store,
                "store cannot be null"
        );
    }

    public Optional<AgentTaskView> findByTaskId(String taskId) {
        return store.load(requireText(taskId, "taskId"))
                .map(AgentTaskView::from);
    }

    public List<AgentTaskView> listByConversationId(
            String conversationId
    ) {
        return store.list(requireText(conversationId, "conversationId"))
                .stream()
                .map(AgentTaskView::from)
                .toList();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
        return value.trim();
    }
}
