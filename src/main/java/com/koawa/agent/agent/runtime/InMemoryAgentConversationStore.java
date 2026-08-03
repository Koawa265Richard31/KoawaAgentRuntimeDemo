package com.koawa.agent.agent.runtime;

import com.koawa.agent.agent.domain.AgentConversationTurn;
import com.koawa.agent.agent.exception.AgentConversationTurnConflictException;
import com.koawa.agent.agent.service.AgentConversationStore;
import com.koawa.agent.framework.convention.ChatMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryAgentConversationStore
        implements AgentConversationStore {

    private static final int MAX_TURNS = 10;

    private final Map<ConversationKey, List<AgentConversationTurn>>
            conversations = new HashMap<>();
    private final Map<TurnIdentity, AgentConversationTurn>
            turnsByIdentity = new HashMap<>();

    @Override
    public synchronized List<ChatMessage> load(
            String conversationId,
            String userId
    ) {
        List<AgentConversationTurn> turns = conversations.get(
                new ConversationKey(
                        requireText(conversationId, "conversationId"),
                        normalizeUserId(userId)
                )
        );
        if (turns == null) {
            return List.of();
        }

        int fromIndex = Math.max(0, turns.size() - MAX_TURNS);
        List<ChatMessage> messages = new ArrayList<>(
                (turns.size() - fromIndex) * 2
        );
        for (int index = fromIndex; index < turns.size(); index++) {
            AgentConversationTurn turn = turns.get(index);
            messages.add(ChatMessage.user(turn.input().content()));
            messages.add(ChatMessage.assistant(turn.outputContent()));
        }
        return List.copyOf(messages);
    }

    @Override
    public synchronized void appendTurn(AgentConversationTurn turn) {
        AgentConversationTurn actualTurn = Objects.requireNonNull(
                turn,
                "turn cannot be null"
        );
        TurnIdentity identity = new TurnIdentity(
                actualTurn.taskId(),
                actualTurn.terminalStepIndex()
        );
        AgentConversationTurn existing = turnsByIdentity.get(identity);
        if (existing != null) {
            requireMatching(existing, actualTurn);
            return;
        }

        ConversationKey key = new ConversationKey(
                actualTurn.conversationId(),
                actualTurn.userId()
        );
        conversations.computeIfAbsent(
                key,
                ignored -> new ArrayList<>()
        ).add(actualTurn);
        turnsByIdentity.put(identity, actualTurn);
    }

    private void requireMatching(
            AgentConversationTurn existing,
            AgentConversationTurn attempted
    ) {
        if (!existing.equals(attempted)) {
            throw new AgentConversationTurnConflictException(
                    attempted.taskId(),
                    attempted.terminalStepIndex()
            );
        }
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank()
                ? null
                : userId.trim();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
        return value.trim();
    }

    private record ConversationKey(
            String conversationId,
            String userId
    ) {
    }

    private record TurnIdentity(
            String taskId,
            int terminalStepIndex
    ) {
    }
}
