package com.koawa.agent.agent.exception;

import java.util.Objects;

/**
 * Raised when an execution attempt presents a missing, expired or fenced
 * permit.
 *
 * <p>The current execution must stop at the next safe boundary. This failure
 * must not be downgraded to an automatic re-acquire or ordinary checkpoint
 * retry.
 */
public final class AgentExecutionLeaseLostException
        extends RuntimeException {

    private final String taskId;
    private final long fencingToken;
    private final Reason reason;

    public AgentExecutionLeaseLostException(
            String taskId,
            long fencingToken,
            Reason reason
    ) {
        this(taskId, fencingToken, reason, null);
    }

    public AgentExecutionLeaseLostException(
            String taskId,
            long fencingToken,
            Reason reason,
            Throwable cause
    ) {
        super("execution lease lost for task " + taskId
                + " with fencing token " + fencingToken
                + ": " + reason, cause);
        this.taskId = taskId;
        this.fencingToken = fencingToken;
        this.reason = Objects.requireNonNull(
                reason,
                "reason cannot be null"
        );
    }

    public String getTaskId() {
        return taskId;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        LEASE_MISSING,
        OWNER_OR_TOKEN_MISMATCH,
        LEASE_EXPIRED,
        RENEWAL_FAILED
    }
}
