package com.koawa.agent.agent.runtime;

import com.koawa.agent.agent.service.AgentConversationHistoryLoader;
import com.koawa.agent.framework.convention.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public final class InMemoryAgentConversationStore
        implements AgentConversationHistoryLoader {

    private static final int MAX_MESSAGES = 20;

    private final ConcurrentMap<String, List<ChatMessage>> conversations =
            new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        List<ChatMessage> messages = conversations.get(key(conversationId, userId));
        return messages == null ? List.of() : List.copyOf(messages);
    }

    public void appendTurn(
            String conversationId,
            String userId,
            String question,
            String answer
    ) {
        conversations.compute(key(conversationId, userId), (ignored, existing) -> {
            List<ChatMessage> updated = existing == null
                    ? new ArrayList<>()
                    : new ArrayList<>(existing);
            updated.add(ChatMessage.user(question));
            updated.add(ChatMessage.assistant(answer));
            int fromIndex = Math.max(0, updated.size() - MAX_MESSAGES);
            return List.copyOf(updated.subList(fromIndex, updated.size()));
        });
    }

    private String key(String conversationId, String userId) {
        return normalize(userId) + ":" + normalize(conversationId);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "anonymous" : value.trim();
    }
}
