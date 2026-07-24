package com.koawa.agent.agent.checkpoint;

/**
 * Raised when checkpoint JSON cannot be encoded, decoded, or migrated.
 */
public final class AgentTaskSnapshotCodecException
        extends RuntimeException {

    public AgentTaskSnapshotCodecException(String message) {
        super(message);
    }

    public AgentTaskSnapshotCodecException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
