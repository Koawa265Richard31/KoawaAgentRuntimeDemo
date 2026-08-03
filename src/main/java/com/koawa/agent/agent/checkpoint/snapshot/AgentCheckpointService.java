package com.koawa.agent.agent.checkpoint.snapshot;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionPermit;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentConversationTurn;
import com.koawa.agent.agent.domain.AgentConversationTurnInput;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStep;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import com.koawa.agent.agent.service.AgentConversationStore;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Application service for checkpoints and their terminal Conversation commit.
 *
 * <p>Ordinary Step writes remain checkpoint-only. A terminal write uses one
 * transaction to save the lifecycle revision and append its deliverable Turn.
 * Runtime state carries its source revision so stale workers cannot adopt the
 * latest revision implicitly.</p>
 */
public final class AgentCheckpointService {

    private final AgentCheckpointStore store;
    private final AgentTaskSnapshotMapper mapper;
    private final Clock clock;
    private final AgentFencedCheckpointWriter fencedWriter;
    private final AgentConversationStore conversationStore;
    private final TransactionOperations terminalTransactions;

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
        this(store, mapper, clock, fencedWriter, null, null);
    }

    /**
     * Creates the runtime service with the shared terminal transaction.
     *
     * <p>The transaction operations and conversation store must use the same
     * database resource as the checkpoint store in production.</p>
     */
    public AgentCheckpointService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper,
            Clock clock,
            AgentFencedCheckpointWriter fencedWriter,
            AgentConversationStore conversationStore,
            TransactionOperations terminalTransactions
    ) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.fencedWriter = fencedWriter;
        if ((conversationStore == null) != (terminalTransactions == null)) {
            throw new IllegalArgumentException(
                    "conversationStore and terminalTransactions "
                            + "must be configured together"
            );
        }
        this.conversationStore = conversationStore;
        this.terminalTransactions = terminalTransactions;
    }

    /**
     * Creates revision zero for a task that has not started executing.
     */
    public AgentTaskSnapshot create(AgentState initialState) {
        Objects.requireNonNull(initialState, "initialState cannot be null");
        if (initialState.getCurrentStep() != 0
                || initialState.getCheckpointRevision() != 0
                || initialState.getSteps() != null
                && !initialState.getSteps().isEmpty()) {
            throw new IllegalArgumentException(
                    "an initial checkpoint must start before step zero"
            );
        }
        initializeCurrentTurnInput(initialState);

        Instant now = clock.instant();
        AgentTaskSnapshot snapshot = mapper.toSnapshot(
                initialState,
                AgentTaskStatus.RUNNING,
                0,
                null,
                now,
                now
        );
        AgentTaskSnapshot saved = store.save(
                snapshot,
                AgentCheckpointStore.NO_REVISION
        );
        initialState.setCheckpointRevision(saved.revision());
        return saved;
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
                pendingInterrupt,
                null
        );
        AgentTaskSnapshot saved = persist(pending, null);
        state.setCheckpointRevision(saved.revision());
        return saved;
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
                pendingInterrupt,
                null
        );
        AgentTaskSnapshot saved = persist(pending, permit);
        state.setCheckpointRevision(saved.revision());
        return saved;
    }

    /**
     * Atomically saves a terminal lifecycle revision and its deliverable
     * conversation turn, when the stop reason produces one.
     */
    public AgentTaskSnapshot commitTerminal(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt
    ) {
        return commitTerminal(
                state,
                status,
                pendingInterrupt,
                null,
                null
        );
    }

    /**
     * Fenced variant used by a claimed Resume execution.
     */
    public AgentTaskSnapshot commitTerminal(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt,
            AgentExecutionPermit permit
    ) {
        return commitTerminal(
                state,
                status,
                pendingInterrupt,
                null,
                Objects.requireNonNull(permit, "permit cannot be null")
        );
    }

    /**
     * Exact-revision variant used when repairing a loaded terminal boundary.
     */
    public AgentTaskSnapshot commitTerminal(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt,
            long expectedRevision
    ) {
        return commitTerminal(
                state,
                status,
                pendingInterrupt,
                requireRevision(expectedRevision),
                null
        );
    }

    /**
     * Exact-revision and fenced terminal repair variant.
     */
    public AgentTaskSnapshot commitTerminal(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt,
            long expectedRevision,
            AgentExecutionPermit permit
    ) {
        return commitTerminal(
                state,
                status,
                pendingInterrupt,
                requireRevision(expectedRevision),
                Objects.requireNonNull(permit, "permit cannot be null")
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
            PendingInterrupt pendingInterrupt,
            Long expectedRevision
    ) {
        Objects.requireNonNull(state, "state cannot be null");
        AgentTaskSnapshot current = requireSnapshot(state.getTaskId());
        long sourceRevision = expectedRevision == null
                ? state.getCheckpointRevision()
                : expectedRevision;
        if (sourceRevision < 0) {
            throw new IllegalArgumentException(
                    "checkpointRevision cannot be negative"
            );
        }
        if (current.revision() != sourceRevision) {
            throw new CheckpointConflictException(
                    state.getTaskId(),
                    sourceRevision,
                    current.revision()
            );
        }
        if (current.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "checkpoint revision limit reached for task "
                            + state.getTaskId()
            );
        }

        Instant now = clock.instant();
        AgentTaskSnapshot next = mapper.toSnapshot(
                state,
                status,
                current.revision() + 1,
                pendingInterrupt,
                current.createdAt(),
                now.isBefore(current.updatedAt())
                        ? current.updatedAt()
                        : now
        );
        return new PendingSave(current, next, current.revision());
    }

    private AgentTaskSnapshot commitTerminal(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt,
            Long expectedRevision,
            AgentExecutionPermit permit
    ) {
        requireTerminalCommitter();
        AgentConversationTurn turn = terminalTurn(
                state,
                status,
                pendingInterrupt
        );

        AgentTaskSnapshot saved = Objects.requireNonNull(
                terminalTransactions.execute(transactionStatus -> {
                    PendingSave pending = prepareSave(
                            state,
                            status,
                            pendingInterrupt,
                            expectedRevision
                    );
                    requireSameExecutionBoundary(
                            pending.current(),
                            state
                    );
                    AgentTaskSnapshot persisted = persist(
                            pending,
                            permit
                    );
                    if (turn != null) {
                        conversationStore.appendTurn(turn);
                    }
                    return persisted;
                }),
                "terminal transaction returned null"
        );
        state.setCheckpointRevision(saved.revision());
        return saved;
    }

    private AgentTaskSnapshot persist(
            PendingSave pending,
            AgentExecutionPermit permit
    ) {
        if (permit == null) {
            return store.save(
                    pending.snapshot(),
                    pending.expectedRevision()
            );
        }
        if (fencedWriter == null) {
            throw new IllegalStateException(
                    "fenced checkpoint writer is not configured"
            );
        }
        return fencedWriter.save(
                pending.snapshot(),
                pending.expectedRevision(),
                permit
        );
    }

    private AgentConversationTurn terminalTurn(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt
    ) {
        Objects.requireNonNull(state, "state cannot be null");
        AgentStopReason stopReason = Objects.requireNonNull(
                state.getStopReason(),
                "terminal state stopReason cannot be null"
        );
        AgentTaskStatus expectedStatus = taskStatus(stopReason);
        if (status != expectedStatus) {
            throw new IllegalArgumentException(
                    "terminal status does not match stop reason"
            );
        }
        if (stopReason != AgentStopReason.ASK_CLARIFICATION
                && pendingInterrupt != null) {
            throw new IllegalArgumentException(
                    "only clarification can keep a pending interrupt"
            );
        }
        if (!isDeliverable(stopReason)) {
            return null;
        }

        AgentStep terminalStep = lastStep(state);
        AgentActionType expectedAction =
                stopReason == AgentStopReason.FINAL_ANSWER
                        ? AgentActionType.FINAL_ANSWER
                        : AgentActionType.ASK_CLARIFICATION;
        if (terminalStep.getAction() == null
                || terminalStep.getObservation() == null
                || terminalStep.getAction().getType() != expectedAction
                || terminalStep.getObservation().getActionType()
                        != expectedAction
                || !terminalStep.getObservation().isSuccess()
                || state.getCurrentStep()
                        != terminalStep.getStepIndex() + 1) {
            throw new IllegalStateException(
                    "deliverable state has no matching terminal step"
            );
        }

        String output = terminalStep.getObservation().getContent();
        if (!Objects.equals(output, state.getFinalAnswer())) {
            throw new IllegalStateException(
                    "terminal step output and finalAnswer do not match"
            );
        }
        requireMatchingInterrupt(stopReason, output, pendingInterrupt);

        return new AgentConversationTurn(
                state.getConversationId(),
                state.getUserId(),
                state.getTaskId(),
                terminalStep.getStepIndex(),
                Objects.requireNonNull(
                        state.getCurrentTurnInput(),
                        "currentTurnInput cannot be null"
                ),
                stopReason == AgentStopReason.FINAL_ANSWER
                        ? AgentConversationTurn.Outcome.FINAL_ANSWER
                        : AgentConversationTurn.Outcome.ASK_CLARIFICATION,
                output
        );
    }

    private void requireMatchingInterrupt(
            AgentStopReason stopReason,
            String output,
            PendingInterrupt pendingInterrupt
    ) {
        if (stopReason == AgentStopReason.FINAL_ANSWER) {
            if (pendingInterrupt != null) {
                throw new IllegalArgumentException(
                        "final answer cannot keep an interrupt"
                );
            }
            return;
        }
        if (pendingInterrupt == null
                || pendingInterrupt.type() != InterruptType.USER_INPUT
                || !Objects.equals(output, pendingInterrupt.prompt())) {
            throw new IllegalStateException(
                    "clarification output and pending prompt must match"
            );
        }
    }

    private AgentStep lastStep(AgentState state) {
        if (state.getSteps() == null || state.getSteps().isEmpty()) {
            throw new IllegalStateException(
                    "deliverable state must contain a terminal step"
            );
        }
        return state.getSteps().get(state.getSteps().size() - 1);
    }

    private boolean isDeliverable(AgentStopReason stopReason) {
        return stopReason == AgentStopReason.FINAL_ANSWER
                || stopReason == AgentStopReason.ASK_CLARIFICATION;
    }

    private AgentTaskStatus taskStatus(AgentStopReason stopReason) {
        return switch (stopReason) {
            case FINAL_ANSWER -> AgentTaskStatus.COMPLETED;
            case ASK_CLARIFICATION ->
                    AgentTaskStatus.WAITING_FOR_INPUT;
            case CANCELLED -> AgentTaskStatus.CANCELLED;
            case TIMEOUT -> AgentTaskStatus.TIMED_OUT;
            case ERROR, MAX_STEPS -> AgentTaskStatus.FAILED;
        };
    }

    private void requireSameExecutionBoundary(
            AgentTaskSnapshot current,
            AgentState state
    ) {
        if (current.status() != AgentTaskStatus.RUNNING
                || current.pendingInterrupt() != null) {
            throw new IllegalStateException(
                    "terminal commit requires a RUNNING boundary"
            );
        }
        AgentState persisted = mapper.toState(current);
        boolean matches = Objects.equals(
                        persisted.getConversationId(),
                        state.getConversationId()
                )
                && Objects.equals(persisted.getUserId(), state.getUserId())
                && Objects.equals(
                        persisted.getOriginalQuestion(),
                        state.getOriginalQuestion()
                )
                && Objects.equals(
                        persisted.getCurrentTurnInput(),
                        state.getCurrentTurnInput()
                )
                && persisted.getCurrentStep() == state.getCurrentStep()
                && persisted.getMaxSteps() == state.getMaxSteps()
                && Objects.equals(
                        persisted.getDeadlineAt(),
                        state.getDeadlineAt()
                )
                && Objects.equals(persisted.getSteps(), state.getSteps())
                && Objects.equals(
                        persisted.getHistorySnapshot(),
                        state.getHistorySnapshot()
                )
                && Objects.equals(
                        persisted.getConsumedUserInputStep(),
                        state.getConsumedUserInputStep()
                );
        if (!matches) {
            throw new IllegalStateException(
                    "terminal state does not match the persisted boundary"
            );
        }
    }

    private void initializeCurrentTurnInput(AgentState state) {
        AgentConversationTurnInput initial =
                AgentConversationTurnInput.originalQuestion(
                        state.getOriginalQuestion()
                );
        if (state.getCurrentTurnInput() == null) {
            state.setCurrentTurnInput(initial);
            return;
        }
        if (!initial.equals(state.getCurrentTurnInput())) {
            throw new IllegalArgumentException(
                    "initial currentTurnInput must be the original question"
            );
        }
    }

    private void requireTerminalCommitter() {
        if (conversationStore == null || terminalTransactions == null) {
            throw new IllegalStateException(
                    "terminal checkpoint committer is not configured"
            );
        }
    }

    private Long requireRevision(long expectedRevision) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be negative"
            );
        }
        return expectedRevision;
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
            AgentTaskSnapshot current,
            AgentTaskSnapshot snapshot,
            long expectedRevision
    ) {
    }
}
