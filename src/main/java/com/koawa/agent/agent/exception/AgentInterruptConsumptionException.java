package com.koawa.agent.agent.exception;

import com.koawa.agent.agent.domain.AgentTaskStatus;

import java.util.Objects;

/**
 * Raised when a USER_INPUT interrupt cannot be consumed.
 *
 * <p>An API layer should map this business conflict to HTTP 409. The failed
 * attempt does not mutate the checkpoint. Retrying is safe only after
 * reloading the task or correcting the submitted interrupt identity/input.
 */
public final class AgentInterruptConsumptionException
        extends RuntimeException {

    private final String taskId;
    private final AgentTaskStatus currentStatus;
    private final Reason reason;

    public AgentInterruptConsumptionException(
            String taskId,
            AgentTaskStatus currentStatus,
            Reason reason
    ) {
        super("cannot consume interrupt for task " + taskId
                + ": " + reason);
        this.taskId = taskId;
        this.currentStatus = Objects.requireNonNull(
                currentStatus,
                "currentStatus cannot be null"
        );
        this.reason = Objects.requireNonNull(
                reason,
                "reason cannot be null"
        );
    }

    public String getTaskId() {
        return taskId;
    }

    public AgentTaskStatus getCurrentStatus() {
        return currentStatus;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        NOT_WAITING_FOR_INPUT,
        INTERRUPT_ID_REQUIRED,
        INTERRUPT_ID_MISMATCH,
        USER_INPUT_REQUIRED
    }
}
