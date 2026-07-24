package com.koawa.agent.agent.checkpoint;

/**
 * Raised when a checkpoint compare-and-set write observes a different revision.
 */
public final class CheckpointConflictException extends RuntimeException {

    private final String taskId;
    private final long expectedRevision;
    private final Long actualRevision;

    public CheckpointConflictException(
            String taskId,
            long expectedRevision,
            Long actualRevision
    ) {
        super("checkpoint revision conflict for task " + taskId
                + ": expected " + expectedRevision
                + ", actual " + (actualRevision == null ? "missing" : actualRevision));
        this.taskId = taskId;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public String getTaskId() {
        return taskId;
    }

    public long getExpectedRevision() {
        return expectedRevision;
    }

    public Long getActualRevision() {
        return actualRevision;
    }
}
