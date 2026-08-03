package com.koawa.agent.agent.service;

import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.runtime.AgentTaskCancellationRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentChatFacadeTest {

    private final AgentChatService chatService = mock(AgentChatService.class);
    private final AgentTaskCancellationRegistry cancellationRegistry =
            mock(AgentTaskCancellationRegistry.class);
    private final AgentChatFacade facade = new AgentChatFacade(
            chatService,
            cancellationRegistry
    );

    @Test
    void shouldReturnRunResultWithoutOwningConversationCommit() {
        AgentRunResult result = result(
                AgentStopReason.FINAL_ANSWER,
                "deliverable answer"
        );
        when(chatService.chat(
                "question",
                "requested-conversation",
                "task-1",
                "user-1"
        )).thenReturn(result);

        AgentRunResult actual = facade.chat(
                "question",
                "requested-conversation",
                "task-1",
                "user-1"
        );

        assertSame(result, actual);
        verify(cancellationRegistry).clear("task-1");
    }

    @Test
    void shouldNotAppendNonDeliverableResult() {
        AgentRunResult result = result(
                AgentStopReason.MAX_STEPS,
                "partial output"
        );
        when(chatService.chat(
                "question",
                "conversation-1",
                "task-1",
                "user-1"
        )).thenReturn(result);

        facade.chat(
                "question",
                "conversation-1",
                "task-1",
                "user-1"
        );

        verify(cancellationRegistry).clear("task-1");
    }

    @Test
    void shouldDelegateCancellation() {
        facade.cancel("task-1");

        verify(cancellationRegistry).cancel("task-1");
        verifyNoInteractions(chatService);
    }

    private AgentRunResult result(
            AgentStopReason stopReason,
            String content
    ) {
        return new AgentRunResult(
                "actual-conversation",
                "task-1",
                stopReason,
                2,
                0,
                null,
                content,
                null
        );
    }
}
