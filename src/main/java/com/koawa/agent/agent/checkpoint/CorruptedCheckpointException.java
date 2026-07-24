package com.koawa.agent.agent.checkpoint;

/**
 * Raised when indexed checkpoint columns disagree with the Snapshot JSON.
 */
public final class CorruptedCheckpointException extends RuntimeException {

    private final String taskId;

    public CorruptedCheckpointException(
            String taskId,
            String message
    ) {
        super("corrupted checkpoint for task " + taskId + ": " + message);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
