package com.koawa.agent.agent.domain;

/**
 * Persistent lifecycle status of an agent task.
 *
 * <p>This status describes whether a task can continue or resume. It is intentionally
 * separate from {@link AgentStopReason}, which explains why a single agent loop stopped.
 */
public enum AgentTaskStatus {

    RUNNING,
    WAITING_FOR_INPUT,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean isWaiting() {
        return this == WAITING_FOR_INPUT || this == WAITING_FOR_APPROVAL;
    }

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT -> true;
            case RUNNING, WAITING_FOR_INPUT, WAITING_FOR_APPROVAL -> false;
        };
    }

    /**
     * Returns whether the lifecycle can be persisted with {@code target} as its next status.
     *
     * <p>Keeping the same status is allowed because checkpoints can be written multiple times
     * while a task remains in the same lifecycle phase.
     */
    public boolean canTransitionTo(AgentTaskStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }

        return switch (this) {
            case RUNNING ->
                    target == WAITING_FOR_INPUT
                            || target == WAITING_FOR_APPROVAL
                            || target == COMPLETED
                            || target == FAILED
                            || target == CANCELLED
                            || target == TIMED_OUT;
            case WAITING_FOR_INPUT, WAITING_FOR_APPROVAL ->
                    target == RUNNING
                            || target == CANCELLED
                            || target == TIMED_OUT;
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT -> false;
        };
    }
}
