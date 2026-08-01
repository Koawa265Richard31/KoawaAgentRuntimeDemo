package com.koawa.agent.agent.exception;

/**
 * Reports an idempotency identity reused with a different immutable payload.
 *
 * <p>The conflicting content is intentionally not retained in the exception
 * because conversation text can be sensitive.</p>
 */
public final class AgentConversationTurnConflictException
        extends RuntimeException {

    private final String taskId;
    private final int terminalStepIndex;

    public AgentConversationTurnConflictException(
            String taskId,
            int terminalStepIndex
    ) {
        super("conversation turn payload conflict for task "
                + taskId + " at terminal step " + terminalStepIndex);
        this.taskId = taskId;
        this.terminalStepIndex = terminalStepIndex;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getTerminalStepIndex() {
        return terminalStepIndex;
    }
}
