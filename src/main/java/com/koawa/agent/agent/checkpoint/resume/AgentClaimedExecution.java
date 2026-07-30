package com.koawa.agent.agent.checkpoint.resume;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionLeaseSession;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;

import java.util.Objects;

/**
 * A restored execution context that owns a live task lease.
 *
 * <p>The permit remains encapsulated by the lease session. Callers receive
 * only the restored state and the fenced checkpoint lifecycle required by
 * the Agent loop. Closing this context stops the heartbeat and releases the
 * lease.
 */
public final class AgentClaimedExecution implements AutoCloseable {

    private final AgentTaskSnapshot snapshot;
    private final AgentState state;
    private final AgentCheckpointLifecycle checkpointLifecycle;
    private final AgentExecutionLeaseSession leaseSession;

    AgentClaimedExecution(
            AgentTaskSnapshot snapshot,
            AgentState state,
            AgentCheckpointLifecycle checkpointLifecycle,
            AgentExecutionLeaseSession leaseSession
    ) {
        this.snapshot = Objects.requireNonNull(
                snapshot,
                "snapshot cannot be null"
        );
        this.state = Objects.requireNonNull(
                state,
                "state cannot be null"
        );
        this.checkpointLifecycle = Objects.requireNonNull(
                checkpointLifecycle,
                "checkpointLifecycle cannot be null"
        );
        this.leaseSession = Objects.requireNonNull(
                leaseSession,
                "leaseSession cannot be null"
        );
        if (!snapshot.taskId().equals(state.getTaskId())) {
            throw new IllegalArgumentException(
                    "snapshot and state taskId must match"
            );
        }
        if (snapshot.nextStep() != state.getCurrentStep()) {
            throw new IllegalArgumentException(
                    "snapshot nextStep and state currentStep must match"
            );
        }
        leaseSession.requireActive();
    }

    public AgentTaskSnapshot snapshot() {
        return snapshot;
    }

    public AgentState state() {
        return state;
    }

    public AgentCheckpointLifecycle checkpointLifecycle() {
        return checkpointLifecycle;
    }

    /**
     * Fails at a caller-selected safe boundary after heartbeat failure.
     */
    public void requireActive() {
        leaseSession.requireActive();
    }

    @Override
    public void close() {
        leaseSession.close();
    }
}
