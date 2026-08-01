package com.koawa.agent.agent.service;

/**
 * Read/write boundary for cross-task conversation history.
 *
 * <p>A turn is appended as one logical user/assistant pair. Implementations
 * must never expose a partially appended turn to readers.
 */
public interface AgentConversationStore
        extends AgentConversationHistoryLoader {

    void appendTurn(
            String conversationId,
            String userId,
            String question,
            String answer
    );
}
