package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentTaskStatus;

import java.util.Objects;

/**
 * Typed result of evaluating a Resume command against one checkpoint.
 */
public record AgentResumeResult(
        String taskId,
        long revision,
        AgentTaskStatus currentStatus,
        NextAction nextAction,
        RejectionReason rejectionReason
) {

    public AgentResumeResult {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException(
                    "taskId must be a non-blank string"
            );
        }
        taskId = taskId.trim();
        if (revision < 0) {
            throw new IllegalArgumentException(
                    "revision cannot be negative"
            );
        }
        Objects.requireNonNull(
                currentStatus,
                "currentStatus cannot be null"
        );
        Objects.requireNonNull(
                nextAction,
                "nextAction cannot be null"
        );
        Objects.requireNonNull(
                rejectionReason,
                "rejectionReason cannot be null"
        );

        boolean rejected = nextAction == NextAction.REJECT;
        boolean hasRejectionReason =
                rejectionReason != RejectionReason.NONE;
        if (rejected != hasRejectionReason) {
            throw new IllegalArgumentException(
                    "rejectionReason must be set exactly when Resume is rejected"
            );
        }
    }

    public static AgentResumeResult proceedWith(
            String taskId,
            long revision,
            AgentTaskStatus currentStatus,
            NextAction nextAction
    ) {
        if (nextAction == NextAction.REJECT) {
            throw new IllegalArgumentException(
                    "use rejected() for a rejected Resume"
            );
        }
        return new AgentResumeResult(
                taskId,
                revision,
                currentStatus,
                nextAction,
                RejectionReason.NONE
        );
    }

    public static AgentResumeResult rejected(
            String taskId,
            long revision,
            AgentTaskStatus currentStatus,
            RejectionReason reason
    ) {
        if (reason == RejectionReason.NONE) {
            throw new IllegalArgumentException(
                    "a rejected Resume requires a reason"
            );
        }
        return new AgentResumeResult(
                taskId,
                revision,
                currentStatus,
                NextAction.REJECT,
                reason
        );
    }

    public boolean accepted() {
        return nextAction != NextAction.REJECT;
    }

    public enum NextAction {
        ACQUIRE_EXECUTION_CLAIM,
        CONSUME_USER_INPUT_INTERRUPT,
        REJECT
    }

    public enum RejectionReason {
        NONE,
        INTERRUPT_ID_REQUIRED,
        INTERRUPT_ID_MISMATCH,
        INTERRUPT_ID_NOT_APPLICABLE,
        APPROVAL_RESUME_NOT_SUPPORTED,
        TERMINAL_STATUS
    }
}
