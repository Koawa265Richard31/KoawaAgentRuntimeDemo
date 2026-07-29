package com.koawa.agent.agent.exception;

/**
 * Raised when runtime state cannot cross the checkpoint serialization boundary.
 */
public final class AgentTaskSnapshotMappingException
        extends RuntimeException {

    public AgentTaskSnapshotMappingException(String message) {
        super(message);
    }

    public AgentTaskSnapshotMappingException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
