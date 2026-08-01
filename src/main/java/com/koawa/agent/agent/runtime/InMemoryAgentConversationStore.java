package com.koawa.agent.agent.runtime;

import com.koawa.agent.agent.service.AgentConversationStore;
import com.koawa.agent.framework.convention.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public final class InMemoryAgentConversationStore
        implements AgentConversationStore {

    private static final int MAX_MESSAGES = 20;

    private final ConcurrentMap<ConversationKey, List<ChatMessage>>
            conversations = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        List<ChatMessage> messages = conversations.get(
                key(conversationId, userId)
        );
        return messages == null ? List.of() : detachedCopy(messages);
    }

    @Override
    public void appendTurn(
            String conversationId,
            String userId,
            String question,
            String answer
    ) {
        requireText(question, "question");
        requireText(answer, "answer");
        conversations.compute(
                key(conversationId, userId),
                (ignored, existing) -> append(
                        existing,
                        question,
                        answer
                )
        );
    }

    private List<ChatMessage> append(
            List<ChatMessage> existing,
            String question,
            String answer
    ) {
        List<ChatMessage> updated = existing == null
                ? new ArrayList<>()
                : new ArrayList<>(existing);
        updated.add(ChatMessage.user(question));
        updated.add(ChatMessage.assistant(answer));
        int fromIndex = Math.max(0, updated.size() - MAX_MESSAGES);
        return List.copyOf(updated.subList(fromIndex, updated.size()));
    }

    private List<ChatMessage> detachedCopy(List<ChatMessage> messages) {
        List<ChatMessage> copy = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            ChatMessage actualMessage = Objects.requireNonNull(
                    message,
                    "stored message cannot be null"
            );
            copy.add(new ChatMessage(
                    actualMessage.getRole(),
                    actualMessage.getContent()
            ));
        }
        return List.copyOf(copy);
    }

    private ConversationKey key(String conversationId, String userId) {
        return new ConversationKey(
                normalize(conversationId),
                normalize(userId)
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "anonymous" : value.trim();
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
    }

    private record ConversationKey(
            String conversationId,
            String userId
    ) {
    }
}
