package com.koawa.agent.agent.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentConversationTurnTest {

    @Test
    void shouldNormalizeIdentifiersWithoutTrimmingMessageContent() {
        AgentConversationTurn turn = new AgentConversationTurn(
                " conversation-1 ",
                " ",
                " task-1 ",
                2,
                AgentConversationTurnInput.originalQuestion(
                        " preserve input spaces "
                ),
                AgentConversationTurn.Outcome.FINAL_ANSWER,
                " preserve output spaces "
        );

        assertEquals("conversation-1", turn.conversationId());
        assertNull(turn.userId());
        assertEquals("task-1", turn.taskId());
        assertEquals(" preserve input spaces ", turn.input().content());
        assertEquals(" preserve output spaces ", turn.outputContent());
    }

    @Test
    void shouldRequireInterruptIdentityOnlyForInterruptReply() {
        AgentConversationTurnInput reply =
                AgentConversationTurnInput.interruptReply(
                        " user reply ",
                        " interrupt-1 "
                );

        assertEquals(" user reply ", reply.content());
        assertEquals("interrupt-1", reply.sourceInterruptId());
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentConversationTurnInput.interruptReply(
                        "reply",
                        " "
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentConversationTurnInput(
                        AgentConversationTurnInput.Type.ORIGINAL_QUESTION,
                        "question",
                        "interrupt-1"
                )
        );
    }

    @Test
    void shouldRejectInvalidTurnBeforeItReachesAStore() {
        AgentConversationTurnInput input =
                AgentConversationTurnInput.originalQuestion("question");

        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentConversationTurn(
                        "conversation-1",
                        "user-1",
                        "task-1",
                        -1,
                        input,
                        AgentConversationTurn.Outcome.FINAL_ANSWER,
                        "answer"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentConversationTurn(
                        "conversation-1",
                        "user-1",
                        "task-1",
                        0,
                        input,
                        AgentConversationTurn.Outcome.FINAL_ANSWER,
                        " "
                )
        );
    }
}
