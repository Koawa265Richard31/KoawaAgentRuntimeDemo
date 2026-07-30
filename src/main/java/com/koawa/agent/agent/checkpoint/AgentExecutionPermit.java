package com.koawa.agent.agent.checkpoint;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable proof that one execution attempt currently owns a task lease.
 *
 * <p>The constructor is package-private so permits are issued by a lease
 * store rather than assembled by callers.
 */
public final class AgentExecutionPermit {

    private final String taskId;
    private final String ownerId;
    private final long fencingToken;
    private final Instant expiresAt;

    AgentExecutionPermit(
            String taskId,
            String ownerId,
            long fencingToken,
            Instant expiresAt
    ) {
        this.taskId = requireText(taskId, "taskId");
        this.ownerId = requireText(ownerId, "ownerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException(
                    "fencingToken must be positive"
            );
        }
        this.fencingToken = fencingToken;
        this.expiresAt = Objects.requireNonNull(
                expiresAt,
                "expiresAt cannot be null"
        );
    }

    public String taskId() {
        return taskId;
    }

    public String ownerId() {
        return ownerId;
    }

    public long fencingToken() {
        return fencingToken;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof AgentExecutionPermit that)) {
            return false;
        }
        return fencingToken == that.fencingToken
                && taskId.equals(that.taskId)
                && ownerId.equals(that.ownerId)
                && expiresAt.equals(that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                taskId,
                ownerId,
                fencingToken,
                expiresAt
        );
    }

    /**
     * Omits ownerId so accidental logging does not expose the full owner
     * identity.
     */
    @Override
    public String toString() {
        return "AgentExecutionPermit[taskId=" + taskId
                + ", fencingToken=" + fencingToken
                + ", expiresAt=" + expiresAt + "]";
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
        return value.trim();
    }
}
