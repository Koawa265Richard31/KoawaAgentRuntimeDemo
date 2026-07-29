package com.koawa.agent.agent.checkpoint;

/**
 * Immutable request to assess whether a persisted task may be resumed.
 *
 * <p>M0-S1 defines resume eligibility, M0-S3 consumes {@code userInput},
 * and M0-S4 will acquire execution ownership.
 */
public record AgentResumeCommand(
        String taskId,
        long expectedRevision,
        String interruptId,
        String userInput
) {

    public AgentResumeCommand(
            String taskId,
            long expectedRevision,
            String interruptId
    ) {
        this(taskId, expectedRevision, interruptId, null);
    }

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
