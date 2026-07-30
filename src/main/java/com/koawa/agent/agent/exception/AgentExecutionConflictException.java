package com.koawa.agent.agent.exception;

import java.time.Instant;
import java.util.Objects;

/**
 * Raised when another execution attempt still owns an active task lease.
 *
 * <p>An API layer may map this conflict to HTTP 409 and expose retryAt, but
 * must not expose the current owner identity.
 */
public final class AgentExecutionConflictException
        extends RuntimeException {

    private final String taskId;
    private final Instant retryAt;

    public AgentExecutionConflictException(
            String taskId,
            Instant retryAt
    ) {
        super("execution lease is active for task " + taskId
                + " until " + retryAt);
        this.taskId = taskId;
        this.retryAt = Objects.requireNonNull(
                retryAt,
                "retryAt cannot be null"
        );
    }

    public String getTaskId() {
        return taskId;
    }

    public Instant getRetryAt() {
        return retryAt;
    }
}
