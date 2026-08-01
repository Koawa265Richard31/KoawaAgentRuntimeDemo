package com.koawa.agent.agent.domain;

import java.util.Objects;

/**
 * Immutable user input that starts one deliverable conversation turn.
 */
public record AgentConversationTurnInput(
        Type type,
        String content,
        String sourceInterruptId
) {

    public AgentConversationTurnInput {
        Objects.requireNonNull(type, "type cannot be null");
        requireText(content, "content");

        switch (type) {
            case ORIGINAL_QUESTION -> {
                if (sourceInterruptId != null) {
                    throw new IllegalArgumentException(
                            "original question cannot reference an interrupt"
                    );
                }
            }
            case INTERRUPT_REPLY -> sourceInterruptId = requireText(
                    sourceInterruptId,
                    "sourceInterruptId"
            );
        }
    }

    public static AgentConversationTurnInput originalQuestion(
            String content
    ) {
        return new AgentConversationTurnInput(
                Type.ORIGINAL_QUESTION,
                content,
                null
        );
    }

    public static AgentConversationTurnInput interruptReply(
            String content,
            String sourceInterruptId
    ) {
        return new AgentConversationTurnInput(
                Type.INTERRUPT_REPLY,
                content,
                sourceInterruptId
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
        return value.trim();
    }

    public enum Type {
        ORIGINAL_QUESTION,
        INTERRUPT_REPLY
    }
}
