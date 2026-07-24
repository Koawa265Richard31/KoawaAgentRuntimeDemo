package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentTaskSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the latest checkpoint of an agent task.
 */
public interface AgentCheckpointStore {

    long NO_REVISION = -1;

    /**
     * Atomically saves a snapshot when the stored revision matches {@code expectedRevision}.
     *
     * <p>A new task must use {@link #NO_REVISION} and revision {@code 0}. An existing task must
     * advance its revision by exactly one.
     *
     * @throws CheckpointConflictException when another writer has changed the task
     */
    AgentTaskSnapshot save(
            AgentTaskSnapshot snapshot,
            long expectedRevision
    );

    Optional<AgentTaskSnapshot> load(String taskId);

    /**
     * Returns the latest snapshot of every task in a conversation, newest first.
     */
    List<AgentTaskSnapshot> list(String conversationId);

    /**
     * Deletes a checkpoint for retention or administrative cleanup.
     *
     * <p>Normal task completion must retain its terminal checkpoint for audit.
     */
    void delete(String taskId);
}
