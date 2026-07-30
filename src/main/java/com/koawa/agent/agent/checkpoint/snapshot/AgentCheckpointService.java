package com.koawa.agent.agent.checkpoint.snapshot;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionPermit;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Application service for creating, updating, and loading agent checkpoints.
 */
public final class AgentCheckpointService {

    private final AgentCheckpointStore store;
    private final AgentTaskSnapshotMapper mapper;
    private final Clock clock;
    private final AgentFencedCheckpointWriter fencedWriter;

    public AgentCheckpointService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper
    ) {
        this(store, mapper, Clock.systemUTC());
    }

    public AgentCheckpointService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper,
            Clock clock
    ) {
        this(store, mapper, clock, null);
    }

    public AgentCheckpointService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper,
            Clock clock,
            AgentFencedCheckpointWriter fencedWriter
    ) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.fencedWriter = fencedWriter;
    }

    /**
     * Creates revision zero for a task that has not started executing.
     */
    public AgentTaskSnapshot create(AgentState initialState) {
        Objects.requireNonNull(initialState, "initialState cannot be null");
        if (initialState.getCurrentStep() != 0
                || initialState.getSteps() != null
                && !initialState.getSteps().isEmpty()) {
            throw new IllegalArgumentException(
                    "an initial checkpoint must start before step zero"
            );
        }

        Instant now = clock.instant();
        AgentTaskSnapshot snapshot = mapper.toSnapshot(
                initialState,
                AgentTaskStatus.RUNNING,
                0,
                null,
                now,
                now
        );
        return store.save(snapshot, AgentCheckpointStore.NO_REVISION);
    }

    /**
     * Saves the next revision of an existing task.
     */
    public AgentTaskSnapshot save(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt
    ) {
        PendingSave pending = prepareSave(
                state,
                status,
                pendingInterrupt
        );
        return store.save(
                pending.snapshot(),
                pending.expectedRevision()
        );
    }

    /**
     * Saves through a writer that atomically verifies the execution permit.
     */
    public AgentTaskSnapshot save(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt,
            AgentExecutionPermit permit
    ) {
        if (fencedWriter == null) {
            throw new IllegalStateException(
                    "fenced checkpoint writer is not configured"
            );
        }
        PendingSave pending = prepareSave(
                state,
                status,
                pendingInterrupt
        );
        return fencedWriter.save(
                pending.snapshot(),
                pending.expectedRevision(),
                permit
        );
    }

    /**
     * Loads checkpoint data without claiming or resuming task execution.
     */
    public Optional<LoadedAgentCheckpoint> load(String taskId) {
        return store.load(taskId)
                .map(snapshot -> new LoadedAgentCheckpoint(
                        snapshot,
                        mapper.toState(snapshot)));
    }

    private AgentTaskSnapshot requireSnapshot(String taskId) {
        return store.load(taskId)
                .orElseThrow(() ->
                        new CheckpointNotFoundException(taskId));
    }

    private PendingSave prepareSave(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt
    ) {
        Objects.requireNonNull(state, "state cannot be null");
        AgentTaskSnapshot current = requireSnapshot(state.getTaskId());
        if (current.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "checkpoint revision limit reached for task "
                            + state.getTaskId()
            );
        }

        AgentTaskSnapshot next = mapper.toSnapshot(
                state,
                status,
                current.revision() + 1,
                pendingInterrupt,
                current.createdAt(),
                clock.instant()
        );
        return new PendingSave(next, current.revision());
    }

    public record LoadedAgentCheckpoint(
            AgentTaskSnapshot snapshot,
            AgentState state
    ) {

        public LoadedAgentCheckpoint {
            Objects.requireNonNull(snapshot, "snapshot cannot be null");
            Objects.requireNonNull(state, "state cannot be null");
        }
    }

    private record PendingSave(
            AgentTaskSnapshot snapshot,
            long expectedRevision
    ) {
    }
}
