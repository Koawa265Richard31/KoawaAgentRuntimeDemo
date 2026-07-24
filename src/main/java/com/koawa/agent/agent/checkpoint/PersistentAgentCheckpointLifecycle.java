package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycleException;

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

    public PersistentAgentCheckpointLifecycle(
            AgentCheckpointService checkpointService,
            Clock clock
    ) {
        this(
                checkpointService,
                clock,
                () -> UUID.randomUUID().toString()
        );
    }

    public PersistentAgentCheckpointLifecycle(
            AgentCheckpointService checkpointService,
            Clock clock,
            Supplier<String> interruptIdSupplier
    ) {
        this.checkpointService = Objects.requireNonNull(
                checkpointService,
                "checkpointService cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.interruptIdSupplier = Objects.requireNonNull(
                interruptIdSupplier,
                "interruptIdSupplier cannot be null");
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
                () -> checkpointService.save(
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
                () -> checkpointService.save(
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
            prompt = "Additional user input is required";
        }
        return new PendingInterrupt(
                interruptIdSupplier.get(),
                InterruptType.USER_INPUT,
                prompt,
                Map.of(),
                clock.instant());
    }

    private void execute(
            String operation,
            AgentState state,
            Runnable action
    ) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            throw new AgentCheckpointLifecycleException(
                    "failed to " + operation
                            + " for task " + state.getTaskId(),
                    exception);
        }
    }
}
