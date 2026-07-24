package com.koawa.agent.agent.checkpoint;

/**
 * Raised when an operation requires a checkpoint that does not exist.
 */
public final class CheckpointNotFoundException extends RuntimeException {

    private final String taskId;

    public CheckpointNotFoundException(String taskId) {
        super("checkpoint not found for task " + taskId);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
