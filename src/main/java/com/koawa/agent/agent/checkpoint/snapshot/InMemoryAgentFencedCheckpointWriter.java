package com.koawa.agent.agent.checkpoint.snapshot;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionLeaseStore;
import com.koawa.agent.agent.checkpoint.lease.AgentExecutionPermit;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;

import java.time.Clock;
import java.util.Objects;

/**
 * Deterministic local implementation of permit-aware checkpoint writes.
 *
 * <p>The lease check and checkpoint CAS use separate in-memory stores, so
 * this implementation is intended for local and component tests. Only the
 * JDBC implementation provides one-statement database fencing.
 */
public final class InMemoryAgentFencedCheckpointWriter
        implements AgentFencedCheckpointWriter {

    private final AgentCheckpointStore checkpointStore;
    private final AgentExecutionLeaseStore leaseStore;
    private final Clock clock;

    public InMemoryAgentFencedCheckpointWriter(
            AgentCheckpointStore checkpointStore,
            AgentExecutionLeaseStore leaseStore
    ) {
        this(checkpointStore, leaseStore, Clock.systemUTC());
    }

    public InMemoryAgentFencedCheckpointWriter(
            AgentCheckpointStore checkpointStore,
            AgentExecutionLeaseStore leaseStore,
            Clock clock
    ) {
        this.checkpointStore = Objects.requireNonNull(
                checkpointStore,
                "checkpointStore cannot be null"
        );
        this.leaseStore = Objects.requireNonNull(
                leaseStore,
                "leaseStore cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
    }

    @Override
    public AgentTaskSnapshot save(
            AgentTaskSnapshot snapshot,
            long expectedRevision,
            AgentExecutionPermit permit
    ) {
        AgentTaskSnapshot next = Objects.requireNonNull(
                snapshot,
                "snapshot cannot be null"
        );
        AgentExecutionPermit presented = requireMatchingTask(
                next,
                permit
        );
        requireActive(presented);
        return checkpointStore.save(next, expectedRevision);
    }

    private AgentExecutionPermit requireMatchingTask(
            AgentTaskSnapshot snapshot,
            AgentExecutionPermit permit
    ) {
        AgentExecutionPermit presented = Objects.requireNonNull(
                permit,
                "permit cannot be null"
        );
        if (!snapshot.taskId().equals(presented.taskId())) {
            throw new IllegalArgumentException(
                    "permit taskId must match checkpoint taskId"
            );
        }
        return presented;
    }

    private void requireActive(AgentExecutionPermit presented) {
        AgentExecutionPermit current = leaseStore
                .load(presented.taskId())
                .orElseThrow(() -> leaseLost(
                        presented,
                        Reason.LEASE_MISSING
                ));
        if (!current.ownerId().equals(presented.ownerId())
                || current.fencingToken()
                != presented.fencingToken()) {
            throw leaseLost(
                    presented,
                    Reason.OWNER_OR_TOKEN_MISMATCH
            );
        }
        if (!current.expiresAt().isAfter(clock.instant())) {
            throw leaseLost(presented, Reason.LEASE_EXPIRED);
        }
    }

    private AgentExecutionLeaseLostException leaseLost(
            AgentExecutionPermit permit,
            Reason reason
    ) {
        return new AgentExecutionLeaseLostException(
                permit.taskId(),
                permit.fencingToken(),
                reason
        );
    }
}
