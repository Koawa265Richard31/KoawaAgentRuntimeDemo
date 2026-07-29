package com.koawa.agent.agent.checkpoint;

/**
 * Immutable request to assess whether a persisted task may be resumed.
 *
 * <p>M0-S1 only validates resume eligibility. User input is consumed in
 * M0-S3, and execution ownership is acquired in M0-S4.
 */
public record AgentResumeCommand(
        String taskId,
        long expectedRevision,
        String interruptId
) {

    public AgentResumeCommand {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException(
                    "taskId must be a non-blank string"
            );
        }
        taskId = taskId.trim();

        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be negative"
            );
        }

        if (interruptId != null) {
            interruptId = interruptId.trim();
            if (interruptId.isEmpty()) {
                interruptId = null;
            }
        }
    }
}
