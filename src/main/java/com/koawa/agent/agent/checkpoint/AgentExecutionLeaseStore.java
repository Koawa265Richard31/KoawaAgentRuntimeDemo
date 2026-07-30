package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.exception.AgentExecutionConflictException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;

import java.time.Duration;
import java.util.Optional;

/**
 * Coordination port for acquiring, renewing and releasing task execution.
 */
public interface AgentExecutionLeaseStore {

    /**
     * Acquires an expired or absent lease for the expected checkpoint.
     *
     * @throws CheckpointNotFoundException when the task does not exist
     * @throws CheckpointConflictException when the checkpoint revision changed
     * @throws AgentExecutionConflictException when an active lease exists
     */
    AgentExecutionPermit acquire(
            String taskId,
            long expectedRevision,
            Duration leaseDuration
    );

    /**
     * Renews the lease represented by the current permit.
     *
     * @throws AgentExecutionLeaseLostException when the permit is no longer
     * valid
     */
    AgentExecutionPermit renew(
            AgentExecutionPermit permit,
            Duration leaseDuration
    );

    /**
     * Expires the matching lease while retaining its fencing-token history.
     *
     * @throws AgentExecutionLeaseLostException when the permit is no longer
     * valid
     */
    void release(AgentExecutionPermit permit);

    /**
     * Returns the latest lease record, including an expired or released one.
     */
    Optional<AgentExecutionPermit> load(String taskId);
}
