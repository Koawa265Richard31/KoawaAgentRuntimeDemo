package com.koawa.agent.agent.checkpoint.snapshot;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionLeaseSession;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentCheckpointLifecycleException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Persists runtime lifecycle boundaries through {@link AgentCheckpointService}.
 */
public final class PersistentAgentCheckpointLifecycle
        implements AgentCheckpointLifecycle {

    private final AgentCheckpointService checkpointService;
    private final Clock clock;
    private final Supplier<String> interruptIdSupplier;
    private final AgentExecutionLeaseSession leaseSession;

    public PersistentAgentCheckpointLifecycle(
            AgentCheckpointService checkpointService,
            Clock clock
    ) {
        this(
                checkpointService,
                clock,
                () -> UUID.randomUUID().toString(),
                null
        );
    }

    public PersistentAgentCheckpointLifecycle(
            AgentCheckpointService checkpointService,
            Clock clock,
            Supplier<String> interruptIdSupplier
    ) {
        this(
                checkpointService,
                clock,
                interruptIdSupplier,
                null
        );
    }

    public PersistentAgentCheckpointLifecycle(
            AgentCheckpointService checkpointService,
            Clock clock,
            AgentExecutionLeaseSession leaseSession
    ) {
        this(
                checkpointService,
                clock,
                () -> UUID.randomUUID().toString(),
                Objects.requireNonNull(
                        leaseSession,
                        "leaseSession cannot be null"
                )
        );
    }

    public PersistentAgentCheckpointLifecycle(
            AgentCheckpointService checkpointService,
            Clock clock,
            Supplier<String> interruptIdSupplier,
            AgentExecutionLeaseSession leaseSession
    ) {
        this.checkpointService = Objects.requireNonNull(
                checkpointService,
                "checkpointService cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.interruptIdSupplier = Objects.requireNonNull(
                interruptIdSupplier,
                "interruptIdSupplier cannot be null");
        this.leaseSession = leaseSession;
    }

    @Override
    public void initialize(AgentState state) {
        execute("initialize", state, () -> checkpointService.create(state));
    }

    @Override
    public void stepCommitted(AgentState state) {
        execute(
                "save committed step",
                state,
                () -> save(
                        state,
                        AgentTaskStatus.RUNNING,
                        null));
    }

    @Override
    public void completed(AgentState state) {
        AgentStopReason stopReason = Objects.requireNonNull(
                state.getStopReason(),
                "completed state stopReason cannot be null");
        AgentTaskStatus status = toTaskStatus(stopReason);
        PendingInterrupt interrupt =
                stopReason == AgentStopReason.ASK_CLARIFICATION
                        ? clarificationInterrupt(state)
                        : null;

        execute(
                "save completed run",
                state,
                () -> commitTerminal(
                        state,
                        status,
                        interrupt));
    }

    private AgentTaskStatus toTaskStatus(AgentStopReason stopReason) {
        return switch (stopReason) {
            case FINAL_ANSWER -> AgentTaskStatus.COMPLETED;
            case ASK_CLARIFICATION ->
                    AgentTaskStatus.WAITING_FOR_INPUT;
            case CANCELLED -> AgentTaskStatus.CANCELLED;
            case TIMEOUT -> AgentTaskStatus.TIMED_OUT;
            case ERROR, MAX_STEPS -> AgentTaskStatus.FAILED;
        };
    }

    private PendingInterrupt clarificationInterrupt(AgentState state) {
        String prompt = state.getFinalAnswer();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException(
                    "clarification output cannot be blank"
            );
        }
        return new PendingInterrupt(
                interruptIdSupplier.get(),
                InterruptType.USER_INPUT,
                prompt,
                Map.of(),
                clock.instant());
    }

    private void commitTerminal(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt interrupt
    ) {
        if (leaseSession == null) {
            checkpointService.commitTerminal(
                    state,
                    status,
                    interrupt
            );
            return;
        }

        leaseSession.requireActive();
        checkpointService.commitTerminal(
                state,
                status,
                interrupt,
                leaseSession.currentPermit()
        );
    }

    private void save(
            AgentState state,
            AgentTaskStatus status,
            PendingInterrupt interrupt
    ) {
        if (leaseSession == null) {
            checkpointService.save(state, status, interrupt);
            return;
        }

        leaseSession.requireActive();
        checkpointService.save(
                state,
                status,
                interrupt,
                leaseSession.currentPermit()
        );
    }

    private void execute(
            String operation,
            AgentState state,
            Runnable action
    ) {
        try {
            action.run();
        } catch (AgentExecutionLeaseLostException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentCheckpointLifecycleException(
                    "failed to " + operation
                            + " for task " + state.getTaskId(),
                    exception);
        }
    }
}
