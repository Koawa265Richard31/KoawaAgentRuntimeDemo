package com.koawa.agent.agent.service.impl;

import com.koawa.agent.agent.config.AgentRuntimeProperties;
import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.runner.AgentLoopRunner;
import com.koawa.agent.agent.service.AgentConversationHistoryLoader;
import com.koawa.agent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAgentChatServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-20T00:00:00Z");
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(30);

    private final AgentLoopRunner runner = mock(AgentLoopRunner.class);
    private final AgentRuntimeProperties properties = runtimeProperties();
    private final AgentConversationHistoryLoader historyLoader =
            mock(AgentConversationHistoryLoader.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DefaultAgentChatService service =
            new DefaultAgentChatService(
                    runner,
                    properties,
                    historyLoader,
                    clock
            );

    @Test
    void shouldCreateStateAndReturnRunResult() {
        completeRunWithAnswer("answer");

        AgentRunResult result = service.chat(
                "question",
                " conversation-1 ",
                " task-1 ",
                "user-1"
        );

        ArgumentCaptor<AgentState> stateCaptor =
                ArgumentCaptor.forClass(AgentState.class);
        verify(runner).run(stateCaptor.capture());

        AgentState state = stateCaptor.getValue();
        assertEquals("conversation-1", state.getConversationId());
        assertEquals("task-1", state.getTaskId());
        assertEquals("user-1", state.getUserId());
        assertEquals("question", state.getOriginalQuestion());
        assertEquals(0, state.getCurrentStep());
        assertEquals(5, state.getMaxSteps());
        assertEquals(List.of(), state.getHistorySnapshot());
        assertEquals(NOW.plus(TURN_TIMEOUT), state.getDeadlineAt());

        assertEquals("conversation-1", result.conversationId());
        assertEquals("task-1", result.taskId());
        assertEquals(AgentStopReason.FINAL_ANSWER, result.stopReason());
        assertEquals(0, result.stepCount());
        assertEquals(0, result.planningRecoveryAttempts());
        assertNull(result.failureType());
        assertEquals("answer", result.content());
    }

    @Test
    void shouldGenerateConversationAndTaskIdsWhenMissing() {
        completeRunWithAnswer("answer");

        AgentRunResult result = service.chat(
                "question",
                " ",
                null,
                "user-1"
        );

        assertFalse(result.conversationId().isBlank());
        assertFalse(result.taskId().isBlank());
    }

    @Test
    void shouldLoadImmutableHistorySnapshotBeforeRunning() {
        completeRunWithAnswer("answer");
        List<ChatMessage> loadedHistory = new ArrayList<>(List.of(
                ChatMessage.user("previous question"),
                ChatMessage.assistant("previous answer")
        ));
        when(historyLoader.load(
                "conversation-1",
                "user-1"
        )).thenReturn(loadedHistory);

        service.chat(
                "question",
                " conversation-1 ",
                "task-1",
                "user-1"
        );

        ArgumentCaptor<AgentState> stateCaptor =
                ArgumentCaptor.forClass(AgentState.class);
        verify(runner).run(stateCaptor.capture());

        List<ChatMessage> snapshot =
                stateCaptor.getValue().getHistorySnapshot();
        assertEquals(loadedHistory, snapshot);
        assertNotSame(loadedHistory, snapshot);
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.add(ChatMessage.user("new message"))
        );

        loadedHistory.clear();
        assertEquals(2, snapshot.size());
        verify(historyLoader).load("conversation-1", "user-1");
    }

    private void completeRunWithAnswer(String answer) {
        when(runner.run(any(AgentState.class))).thenAnswer(invocation -> {
            AgentState state = invocation.getArgument(0);
            state.setStopReason(AgentStopReason.FINAL_ANSWER);
            state.setFinalAnswer(answer);
            return state;
        });
    }

    private static AgentRuntimeProperties runtimeProperties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxSteps(5);
        properties.setTurnTimeout(TURN_TIMEOUT);
        return properties;
    }
}
