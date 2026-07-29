package com.koawa.agent.agent.runner;

import com.koawa.agent.agent.domain.*;
import com.koawa.agent.agent.event.AgentEventSink;
import com.koawa.agent.agent.exception.AgentCheckpointLifecycleException;
import com.koawa.agent.agent.exception.AgentFailureException;
import com.koawa.agent.agent.executor.AgentActionExecutor;
import com.koawa.agent.agent.planner.AgentPlanner;
import com.koawa.agent.agent.recovery.AgentRecoveryDecision;
import com.koawa.agent.agent.recovery.AgentRecoveryPolicy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Slf4j
public class AgentLoopRunner {

    private final AgentPlanner planner;
    private final AgentActionExecutor executor;
    private final AgentEventSink eventSink;
    private final AgentCancellationChecker cancellationChecker;
    private final AgentRecoveryPolicy recoveryPolicy;
    private final Clock clock;
    private final AgentCheckpointLifecycle checkpointLifecycle;

    private static final int MAX_PLANNING_RECOVERY_ATTEMPTS = 1;

    public AgentLoopRunner(
            AgentPlanner planner,
            AgentActionExecutor executor,
            AgentEventSink eventSink,
            AgentCancellationChecker cancellationChecker,
            AgentRecoveryPolicy recoveryPolicy
    ) {
        this(
                planner,
                executor,
                eventSink,
                cancellationChecker,
                recoveryPolicy,
                Clock.systemUTC(),
                AgentCheckpointLifecycle.NOOP
        );
    }

    public AgentLoopRunner(
            AgentPlanner planner,
            AgentActionExecutor executor,
            AgentEventSink eventSink,
            AgentCancellationChecker cancellationChecker,
            AgentRecoveryPolicy recoveryPolicy,
            Clock clock
    ) {
        this(
                planner,
                executor,
                eventSink,
                cancellationChecker,
                recoveryPolicy,
                clock,
                AgentCheckpointLifecycle.NOOP
        );
    }

    public AgentLoopRunner(
            AgentPlanner planner,
            AgentActionExecutor executor,
            AgentEventSink eventSink,
            AgentCancellationChecker cancellationChecker,
            AgentRecoveryPolicy recoveryPolicy,
            Clock clock,
            AgentCheckpointLifecycle checkpointLifecycle
    ) {
        this.planner = Objects.requireNonNull(
                planner,
                "planner cannot be null"
        );
        this.executor = Objects.requireNonNull(
                executor,
                "executor cannot be null"
        );
        this.eventSink = Objects.requireNonNull(
                eventSink,
                "eventSink cannot be null"
        );
        this.cancellationChecker = Objects.requireNonNull(
                cancellationChecker,
                "cancellationChecker cannot be null"
        );
        this.recoveryPolicy = Objects.requireNonNull(
                recoveryPolicy,
                "recoveryPolicy cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.checkpointLifecycle = Objects.requireNonNull(
                checkpointLifecycle,
                "checkpointLifecycle cannot be null"
        );
    }

    public AgentState run(AgentState state) {
        Objects.requireNonNull(state);

        if (state.getCurrentStep() < 0) {
            throw new IllegalArgumentException(
                    "currentStep cannot be negative: " + state.getCurrentStep()
            );
        }

        if (state.getMaxSteps() <= 0) {
            throw new IllegalArgumentException(
                    "maxSteps must be positive: " + state.getMaxSteps()
            );
        }

        try {
            publishEvent(AgentEvent.turnStarted(
                    state.getConversationId(),
                    state.getTaskId(),
                    state.getCurrentStep()
            ));

            while (state.getCurrentStep() < state.getMaxSteps()) {
                if (completeIfStopped(state)) {
                    return state;
                }

                int stepIndex = state.getCurrentStep();

                publishEvent(AgentEvent.stepStarted(
                        state.getConversationId(),
                        state.getTaskId(),
                        stepIndex
                ));

                AgentAction action;

                while (true) {
                    try {
                        action = Objects.requireNonNull(
                                planner.plan(state),
                                "planner returned null action"
                        );

                        state.setFailureType(null);
                        state.setErrorMessage(null);
                        break;
                    } catch (AgentFailureException exception) {
                        if (completeIfStopped(state)) {
                            return state;
                        }

                        state.setFailureType(exception.getFailureType());
                        state.setErrorMessage(exception.getMessage());

                        AgentRecoveryDecision decision =
                                Objects.requireNonNull(
                                        recoveryPolicy.decide(
                                                exception.getFailureType()
                                        ),
                                    "recoveryPolicy returned null decision"
                                );

                        boolean recoveryBudgetExhausted =
                                state.getPlanningRecoveryAttempts()
                                        >= MAX_PLANNING_RECOVERY_ATTEMPTS;

                        if (decision == AgentRecoveryDecision.STOP
                                || recoveryBudgetExhausted) {
                            throw exception;
                        }

                        state.setPlanningRecoveryAttempts(
                                state.getPlanningRecoveryAttempts() + 1
                        );
                    }
                }

                publishEvent(AgentEvent.actionPlanned(
                        state.getConversationId(),
                        state.getTaskId(),
                        stepIndex,
                        action.getType()
                ));

                if (completeIfStopped(state)) {
                    return state;
                }

                AgentObservation observation = executeAction(action, state);

                publishEvent(AgentEvent.observationReceived(
                        state.getConversationId(),
                        state.getTaskId(),
                        stepIndex,
                        action.getType(),
                        observation.isSuccess(),
                        observation.getContent(),
                        observation.getErrorMessage()
                ));

                AgentStep step = AgentStep.builder()
                        .action(action)
                        .observation(observation)
                        .stepIndex(stepIndex)
                        .build();

                state.getSteps().add(step);
                state.setCurrentStep(stepIndex + 1);
                checkpointLifecycle.stepCommitted(state);

                if (completeIfStopped(state)) {
                    return state;
                }

                if (action.getType().isTerminal()) {
                    finishTerminalAction(state, action, observation);

                    publishEvent(AgentEvent.turnCompleted(
                            state.getConversationId(),
                            state.getTaskId(),
                            state.getStopReason(),
                            state.getFinalAnswer()
                    ));

                    return state;
                }
            }

            if (completeIfStopped(state)) {
                return state;
            }

            state.setStopReason(AgentStopReason.MAX_STEPS);

            publishEvent(AgentEvent.turnCompleted(
                    state.getConversationId(),
                    state.getTaskId(),
                    state.getStopReason(),
                    null
            ));

            return state;
        } catch (RuntimeException exception) {
            if (exception instanceof AgentCheckpointLifecycleException) {
                throw exception;
            }
            if (completeIfStopped(state)) {
                return state;
            }

            AgentFailureType failureType =
                    exception instanceof AgentFailureException failureException
                            ? failureException.getFailureType()
                            : AgentFailureType.UNEXPECTED;

            state.setStopReason(AgentStopReason.ERROR);
            state.setFailureType(failureType);
            state.setErrorMessage(exception.getMessage());

            publishEvent(AgentEvent.turnFailed(
                    state.getConversationId(),
                    state.getTaskId(),
                    state.getErrorMessage()
            ));

            return state;
        }
    }

    private AgentObservation executeAction(
            AgentAction action,
            AgentState state
    ) {
        try {
            return Objects.requireNonNull(
                    executor.execute(action, state),
                    "executor returned null observation"
            );
        } catch (RuntimeException exception) {
            throw new AgentFailureException(
                    AgentFailureType.ACTION_EXECUTION_FAILED,
                    exception.getMessage(),
                    exception
            );
        }
    }

    private boolean completeIfCancelled(AgentState state) {
        if (!cancellationChecker.isCancelled(state.getTaskId())) {
            return false;
        }

        state.setStopReason(AgentStopReason.CANCELLED);
        state.setFailureType(null);
        state.setErrorMessage(null);

        publishEvent(AgentEvent.turnCompleted(
                state.getConversationId(),
                state.getTaskId(),
                AgentStopReason.CANCELLED,
                null
        ));

        return true;
    }

    private boolean completeIfTimedOut(AgentState state) {
        Instant deadlineAt = state.getDeadlineAt();
        if (deadlineAt == null || clock.instant().isBefore(deadlineAt)) {
            return false;
        }

        state.setStopReason(AgentStopReason.TIMEOUT);
        state.setFailureType(null);
        state.setErrorMessage(null);

        publishEvent(AgentEvent.turnCompleted(
                state.getConversationId(),
                state.getTaskId(),
                AgentStopReason.TIMEOUT,
                null
        ));

        return true;
    }

    private boolean completeIfStopped(AgentState state) {
        return completeIfCancelled(state) || completeIfTimedOut(state);
    }

    private void finishTerminalAction(
            AgentState state,
            AgentAction action,
            AgentObservation observation
    ) {
        switch (action.getType()) {
            case FINAL_ANSWER -> {
                state.setStopReason(AgentStopReason.FINAL_ANSWER);
                state.setFinalAnswer(observation.getContent());
            }
            case ASK_CLARIFICATION -> {
                state.setStopReason(AgentStopReason.ASK_CLARIFICATION);
                state.setFinalAnswer(observation.getContent());
            }
            default -> throw new IllegalStateException(
                    "Unsupported terminal action: " + action.getType()
            );
        }
    }

    private void publishEvent(AgentEvent event) {
        try {
            eventSink.publish(event);
        } catch (RuntimeException exception) {
            log.warn(
                    "发布 Agent 事件失败，事件类型：{}，错误：{}",
                    event.type(),
                    exception.getMessage()
            );
            log.debug("Agent 事件发布异常", exception);
        }
    }
}
