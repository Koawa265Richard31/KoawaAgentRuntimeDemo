package com.koawa.agent.agent.runtime;

import com.koawa.agent.agent.domain.AgentConversationTurn;
import com.koawa.agent.agent.domain.AgentConversationTurnInput;
import com.koawa.agent.agent.exception.AgentConversationTurnConflictException;
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
        store.appendTurn(turn(
                "conversation-1",
                "user-1",
                "task-1",
                " question-1 ",
                "answer-1"
        ));
        store.appendTurn(turn(
                "conversation-1",
                "user-1",
                "task-2",
                "question-2",
                "answer-2"
        ));

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
            store.appendTurn(turn(
                    "conversation-1",
                    "user-1",
                    "task-" + turn,
                    "question-" + turn,
                    "answer-" + turn
            ));
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
        store.appendTurn(turn(
                "c",
                "a:b",
                "task-a",
                "question-a",
                "answer-a"
        ));
        store.appendTurn(turn(
                "b:c",
                "a",
                "task-b",
                "question-b",
                "answer-b"
        ));

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
    void shouldTreatAnIdenticalReplayAsIdempotent() {
        AgentConversationTurn turn = turn(
                "conversation-1",
                "user-1",
                "task-1",
                "question",
                "answer"
        );

        store.appendTurn(turn);
        store.appendTurn(turn);

        assertEquals(
                List.of(
                        ChatMessage.user("question"),
                        ChatMessage.assistant("answer")
                ),
                store.load("conversation-1", "user-1")
        );
    }

    @Test
    void shouldRejectIdentityReuseWithDifferentPayload() {
        store.appendTurn(turn(
                "conversation-1",
                "user-1",
                "task-1",
                "question",
                "answer"
        ));

        assertThrows(
                AgentConversationTurnConflictException.class,
                () -> store.appendTurn(turn(
                        "conversation-1",
                        "user-1",
                        "task-1",
                        "question",
                        "different answer"
                ))
        );

        assertEquals(
                ChatMessage.assistant("answer"),
                store.load("conversation-1", "user-1").get(1)
        );
    }

    @Test
    void shouldSeparateAnonymousScopeFromLiteralAnonymousUser() {
        store.appendTurn(turn(
                "conversation-1",
                null,
                "task-null",
                "question-null",
                "answer-null"
        ));
        store.appendTurn(turn(
                "conversation-1",
                "anonymous",
                "task-named",
                "question-named",
                "answer-named"
        ));

        assertEquals(
                ChatMessage.user("question-null"),
                store.load("conversation-1", " ").get(0)
        );
        assertEquals(
                ChatMessage.user("question-named"),
                store.load("conversation-1", "anonymous").get(0)
        );
        assertTrue(store.load("conversation-1", "user-1").isEmpty());
    }

    private AgentConversationTurn turn(
            String conversationId,
            String userId,
            String taskId,
            String inputContent,
            String outputContent
    ) {
        return new AgentConversationTurn(
                conversationId,
                userId,
                taskId,
                0,
                AgentConversationTurnInput.originalQuestion(inputContent),
                AgentConversationTurn.Outcome.FINAL_ANSWER,
                outputContent
        );
    }
}
