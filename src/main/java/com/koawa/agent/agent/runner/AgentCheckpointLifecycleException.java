package com.koawa.agent.agent.runner;

/**
 * Raised when a runtime checkpoint boundary cannot be persisted safely.
 */
public final class AgentCheckpointLifecycleException
        extends RuntimeException {

    public AgentCheckpointLifecycleException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
