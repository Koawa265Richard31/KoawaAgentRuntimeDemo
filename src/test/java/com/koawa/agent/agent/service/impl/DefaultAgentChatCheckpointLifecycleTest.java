package com.koawa.agent.agent.service.impl;

import com.koawa.agent.agent.config.AgentRuntimeProperties;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;
import com.koawa.agent.agent.runner.AgentLoopRunner;
import com.koawa.agent.agent.service.AgentConversationHistoryLoader;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAgentChatCheckpointLifecycleTest {

    @Test
    void shouldInitializeBeforeRunAndCompleteAfterRun() {
        AgentLoopRunner runner = mock(AgentLoopRunner.class);
        AgentCheckpointLifecycle lifecycle =
                mock(AgentCheckpointLifecycle.class);
        AgentConversationHistoryLoader historyLoader =
                mock(AgentConversationHistoryLoader.class);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxSteps(4);
        properties.setTurnTimeout(Duration.ofSeconds(30));
        when(historyLoader.load("conversation-1", "user-1"))
                .thenReturn(List.of());
        when(runner.run(any(AgentState.class))).thenAnswer(invocation -> {
            AgentState state = invocation.getArgument(0);
            state.setStopReason(AgentStopReason.FINAL_ANSWER);
            state.setFinalAnswer("answer");
            return state;
        });
        DefaultAgentChatService service = new DefaultAgentChatService(
                runner,
                properties,
                historyLoader,
                Clock.fixed(
                        Instant.parse("2026-07-24T06:00:00Z"),
                        ZoneOffset.UTC),
                lifecycle);

        service.chat(
                "question",
                "conversation-1",
                "task-1",
                "user-1");

        InOrder order = inOrder(lifecycle, runner);
        order.verify(lifecycle).initialize(any(AgentState.class));
        order.verify(runner).run(any(AgentState.class));
        order.verify(lifecycle).completed(any(AgentState.class));
    }
}
