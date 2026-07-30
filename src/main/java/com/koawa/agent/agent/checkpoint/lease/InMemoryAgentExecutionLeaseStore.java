package com.koawa.agent.agent.checkpoint.lease;

import com.koawa.agent.agent.checkpoint.AgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.exception.AgentExecutionConflictException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Thread-safe in-memory lease store for local runtime use and deterministic
 * tests.
 *
 * <p>Lease transitions are atomic per task. Atomicity across a checkpoint row
 * and a lease row belongs to the PostgreSQL implementation in M0-S4c.
 */
public final class InMemoryAgentExecutionLeaseStore
        implements AgentExecutionLeaseStore {

    private final AgentCheckpointStore checkpointStore;
    private final Clock clock;
    private final Supplier<String> ownerIdSupplier;
    private final ConcurrentMap<String, AgentExecutionPermit> leases =
            new ConcurrentHashMap<>();

    public InMemoryAgentExecutionLeaseStore(
            AgentCheckpointStore checkpointStore
    ) {
        this(
                checkpointStore,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString()
        );
    }

    public InMemoryAgentExecutionLeaseStore(
            AgentCheckpointStore checkpointStore,
            Clock clock,
            Supplier<String> ownerIdSupplier
    ) {
        this.checkpointStore = Objects.requireNonNull(
                checkpointStore,
                "checkpointStore cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.ownerIdSupplier = Objects.requireNonNull(
                ownerIdSupplier,
                "ownerIdSupplier cannot be null"
        );
    }

    @Override
    public AgentExecutionPermit acquire(
            String taskId,
            long expectedRevision,
            Duration leaseDuration
    ) {
        String actualTaskId = requireText(taskId, "taskId");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be negative"
            );
        }
        Duration actualDuration = requirePositiveDuration(leaseDuration);
        requireCheckpointRevision(actualTaskId, expectedRevision);
        Instant now = clock.instant();

        return leases.compute(actualTaskId, (ignored, current) -> {
            if (current != null && current.expiresAt().isAfter(now)) {
                throw new AgentExecutionConflictException(
                        actualTaskId,
                        current.expiresAt()
                );
            }
            long nextToken = current == null
                    ? 1
                    : Math.incrementExact(current.fencingToken());
            return new AgentExecutionPermit(
                    actualTaskId,
                    ownerIdSupplier.get(),
                    nextToken,
                    now.plus(actualDuration)
            );
        });
    }

    @Override
    public AgentExecutionPermit renew(
            AgentExecutionPermit permit,
            Duration leaseDuration
    ) {
        AgentExecutionPermit presented = Objects.requireNonNull(
                permit,
                "permit cannot be null"
        );
        Duration actualDuration = requirePositiveDuration(leaseDuration);
        Instant now = clock.instant();

        return leases.compute(presented.taskId(), (ignored, current) -> {
            requireOwnedActiveLease(current, presented, now);
            return new AgentExecutionPermit(
                    current.taskId(),
                    current.ownerId(),
                    current.fencingToken(),
                    now.plus(actualDuration)
            );
        });
    }

    @Override
    public void release(AgentExecutionPermit permit) {
        AgentExecutionPermit presented = Objects.requireNonNull(
                permit,
                "permit cannot be null"
        );
        Instant now = clock.instant();

        leases.compute(presented.taskId(), (ignored, current) -> {
            requireOwnedActiveLease(current, presented, now);
            return new AgentExecutionPermit(
                    current.taskId(),
                    current.ownerId(),
                    current.fencingToken(),
                    now
            );
        });
    }

    @Override
    public Optional<AgentExecutionPermit> load(String taskId) {
        return Optional.ofNullable(
                leases.get(requireText(taskId, "taskId"))
        );
    }

    private void requireCheckpointRevision(
            String taskId,
            long expectedRevision
    ) {
        AgentTaskSnapshot checkpoint = checkpointStore.load(taskId)
                .orElseThrow(() -> new CheckpointNotFoundException(taskId));
        if (checkpoint.revision() != expectedRevision) {
            throw new CheckpointConflictException(
                    taskId,
                    expectedRevision,
                    checkpoint.revision()
            );
        }
    }

    private void requireOwnedActiveLease(
            AgentExecutionPermit current,
            AgentExecutionPermit presented,
            Instant now
    ) {
        if (current == null) {
            throw leaseLost(presented, Reason.LEASE_MISSING);
        }
        if (!current.ownerId().equals(presented.ownerId())
                || current.fencingToken()
                != presented.fencingToken()) {
            throw leaseLost(
                    presented,
                    Reason.OWNER_OR_TOKEN_MISMATCH
            );
        }
        if (!current.expiresAt().isAfter(now)) {
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

    private Duration requirePositiveDuration(Duration duration) {
        Objects.requireNonNull(
                duration,
                "leaseDuration cannot be null"
        );
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    "leaseDuration must be positive"
            );
        }
        return duration;
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
