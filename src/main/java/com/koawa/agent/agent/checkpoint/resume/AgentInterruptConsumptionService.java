package com.koawa.agent.agent.checkpoint.resume;

import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.domain.AgentConversationTurnInput;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentInterruptConsumptionException;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import com.koawa.agent.framework.convention.ChatMessage;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Atomically consumes one matching USER_INPUT interrupt.
 *
 * <p>The service persists the user's reply as conversation context and moves
 * the task back to RUNNING. It does not claim execution or run the Agent loop.
 */
public final class AgentInterruptConsumptionService {

    private final AgentCheckpointStore store;
    private final AgentTaskSnapshotMapper mapper;
    private final Clock clock;

    public AgentInterruptConsumptionService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper
    ) {
        this(store, mapper, Clock.systemUTC());
    }

    public AgentInterruptConsumptionService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(
                store,
                "store cannot be null"
        );
        this.mapper = Objects.requireNonNull(
                mapper,
                "mapper cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
    }

    public AgentInterruptConsumptionResult consume(
            AgentResumeCommand command
    ) {
        Objects.requireNonNull(command, "command cannot be null");

        AgentTaskSnapshot current = store.load(command.taskId())
                .orElseThrow(() ->
                        new CheckpointNotFoundException(command.taskId()));
        requireExpectedRevision(command, current);
        requireWaitingForInput(current);

        PendingInterrupt interrupt =
                Objects.requireNonNull(current.pendingInterrupt());
        requireMatchingInterrupt(command, current, interrupt);
        requireUserInput(command, current);

        if (current.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "checkpoint revision limit reached for task "
                            + current.taskId()
            );
        }

        AgentState state = mapper.toState(current);
        appendUserInput(state, command.userInput());
        state.setCurrentTurnInput(
                AgentConversationTurnInput.interruptReply(
                        command.userInput(),
                        interrupt.interruptId()
                )
        );
        clearPreviousStop(state);
        markConsumedInputBoundary(state, current);

        AgentTaskSnapshot next = mapper.toSnapshot(
                state,
                AgentTaskStatus.RUNNING,
                current.revision() + 1,
                null,
                current.createdAt(),
                monotonicUpdatedAt(current.updatedAt())
        );
        AgentTaskSnapshot saved = store.save(
                next,
                current.revision()
        );
        state.setCheckpointRevision(saved.revision());

        return new AgentInterruptConsumptionResult(
                interrupt.interruptId(),
                saved,
                state
        );
    }

    private void appendUserInput(
            AgentState state,
            String userInput
    ) {
        List<ChatMessage> history = new ArrayList<>(
                state.getHistorySnapshot() == null
                        ? List.of()
                        : state.getHistorySnapshot()
        );
        history.add(ChatMessage.user(userInput));
        state.setHistorySnapshot(history);
    }

    private void clearPreviousStop(AgentState state) {
        state.setStopReason(null);
        state.setFinalAnswer(null);
        state.setFailureType(null);
        state.setErrorMessage(null);
    }

    private void markConsumedInputBoundary(
            AgentState state,
            AgentTaskSnapshot snapshot
    ) {
        if (snapshot.steps().isEmpty()) {
            return;
        }
        StepSnapshot lastStep =
                snapshot.steps().get(snapshot.steps().size() - 1);
        state.setConsumedUserInputStep(lastStep.stepIndex());
    }

    private void requireExpectedRevision(
            AgentResumeCommand command,
            AgentTaskSnapshot current
    ) {
        if (current.revision() != command.expectedRevision()) {
            throw new CheckpointConflictException(
                    command.taskId(),
                    command.expectedRevision(),
                    current.revision()
            );
        }
    }

    private void requireWaitingForInput(AgentTaskSnapshot current) {
        if (current.status() != AgentTaskStatus.WAITING_FOR_INPUT) {
            throw rejected(
                    current,
                    AgentInterruptConsumptionException.Reason
                            .NOT_WAITING_FOR_INPUT
            );
        }
    }

    private void requireMatchingInterrupt(
            AgentResumeCommand command,
            AgentTaskSnapshot current,
            PendingInterrupt interrupt
    ) {
        if (command.interruptId() == null) {
            throw rejected(
                    current,
                    AgentInterruptConsumptionException.Reason
                            .INTERRUPT_ID_REQUIRED
            );
        }
        if (!interrupt.interruptId().equals(command.interruptId())) {
            throw rejected(
                    current,
                    AgentInterruptConsumptionException.Reason
                            .INTERRUPT_ID_MISMATCH
            );
        }
    }

    private void requireUserInput(
            AgentResumeCommand command,
            AgentTaskSnapshot current
    ) {
        if (command.userInput() == null
                || command.userInput().isBlank()) {
            throw rejected(
                    current,
                    AgentInterruptConsumptionException.Reason
                            .USER_INPUT_REQUIRED
            );
        }
    }

    private AgentInterruptConsumptionException rejected(
            AgentTaskSnapshot current,
            AgentInterruptConsumptionException.Reason reason
    ) {
        return new AgentInterruptConsumptionException(
                current.taskId(),
                current.status(),
                reason
        );
    }

    private Instant monotonicUpdatedAt(Instant currentUpdatedAt) {
        Instant now = clock.instant();
        return now.isBefore(currentUpdatedAt)
                ? currentUpdatedAt
                : now;
    }
}
