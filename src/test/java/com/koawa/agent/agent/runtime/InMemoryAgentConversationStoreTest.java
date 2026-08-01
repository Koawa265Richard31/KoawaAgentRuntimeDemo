package com.koawa.agent.agent.runtime;

import com.koawa.agent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAgentConversationStoreTest {

    private final InMemoryAgentConversationStore store =
            new InMemoryAgentConversationStore();

    @Test
    void shouldAppendWholeTurnsAndReturnDetachedSnapshot() {
        store.appendTurn(
                "conversation-1",
                "user-1",
                " question-1 ",
                "answer-1"
        );
        store.appendTurn(
                "conversation-1",
                "user-1",
                "question-2",
                "answer-2"
        );

        List<ChatMessage> loaded = store.load(
                "conversation-1",
                "user-1"
        );

        assertEquals(List.of(
                ChatMessage.user(" question-1 "),
                ChatMessage.assistant("answer-1"),
                ChatMessage.user("question-2"),
                ChatMessage.assistant("answer-2")
        ), loaded);
        assertThrows(
                UnsupportedOperationException.class,
                () -> loaded.add(ChatMessage.user("unexpected"))
        );

        loaded.get(0).setContent("changed outside the store");
        List<ChatMessage> reloaded = store.load(
                "conversation-1",
                "user-1"
        );
        assertEquals(" question-1 ", reloaded.get(0).getContent());
        assertNotSame(loaded.get(0), reloaded.get(0));
    }

    @Test
    void shouldKeepOnlyTheLatestTenWholeTurns() {
        for (int turn = 1; turn <= 12; turn++) {
            store.appendTurn(
                    "conversation-1",
                    "user-1",
                    "question-" + turn,
                    "answer-" + turn
            );
        }

        List<ChatMessage> loaded = store.load(
                "conversation-1",
                "user-1"
        );

        assertEquals(20, loaded.size());
        assertEquals(ChatMessage.user("question-3"), loaded.get(0));
        assertEquals(
                ChatMessage.assistant("answer-12"),
                loaded.get(19)
        );
    }

    @Test
    void shouldIsolateCompositeConversationKeysWithoutStringCollisions() {
        store.appendTurn("c", "a:b", "question-a", "answer-a");
        store.appendTurn("b:c", "a", "question-b", "answer-b");

        assertEquals(
                List.of(
                        ChatMessage.user("question-a"),
                        ChatMessage.assistant("answer-a")
                ),
                store.load("c", "a:b")
        );
        assertEquals(
                List.of(
                        ChatMessage.user("question-b"),
                        ChatMessage.assistant("answer-b")
                ),
                store.load("b:c", "a")
        );
    }

    @Test
    void shouldRejectInvalidTurnBeforeChangingHistory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.appendTurn(
                        "conversation-1",
                        "user-1",
                        "question",
                        " "
                )
        );

        assertTrue(store.load("conversation-1", "user-1").isEmpty());
    }
}
