package com.koawa.agent.agent.domain;

import java.util.Objects;

/**
 * One complete cross-task conversation projection.
 *
 * <p>The stable identity is {@code taskId + terminalStepIndex}. A turn keeps
 * one user input and one deliverable assistant output together.</p>
 */
public record AgentConversationTurn(
        String conversationId,
        String userId,
        String taskId,
        int terminalStepIndex,
        AgentConversationTurnInput input,
        Outcome outcome,
        String outputContent
) {

    public AgentConversationTurn {
        conversationId = requireText(conversationId, "conversationId");
        userId = normalizeOptionalText(userId);
        taskId = requireText(taskId, "taskId");
        if (terminalStepIndex < 0) {
            throw new IllegalArgumentException(
                    "terminalStepIndex cannot be negative"
            );
        }
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(outcome, "outcome cannot be null");
        requireText(outputContent, "outputContent");
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
        return value.trim();
    }

    public enum Outcome {
        FINAL_ANSWER,
        ASK_CLARIFICATION
    }
}
