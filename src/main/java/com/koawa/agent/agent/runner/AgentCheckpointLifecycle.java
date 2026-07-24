package com.koawa.agent.agent.runner;

import com.koawa.agent.agent.domain.AgentState;

/**
 * Runtime port for checkpointing a task at lifecycle boundaries.
 */
public interface AgentCheckpointLifecycle {

    AgentCheckpointLifecycle NOOP = new AgentCheckpointLifecycle() {
    };

    default void initialize(AgentState state) {
    }

    default void stepCommitted(AgentState state) {
    }

    default void completed(AgentState state) {
    }
}
