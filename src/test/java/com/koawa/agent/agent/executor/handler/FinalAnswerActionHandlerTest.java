package com.koawa.agent.agent.executor.handler;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStep;
import com.koawa.agent.framework.convention.ChatMessage;
import com.koawa.agent.framework.convention.ChatRequest;
import com.koawa.agent.infra.chat.LLMService;
import com.koawa.agent.agent.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinalAnswerActionHandlerTest {

    @Test
    void shouldGenerateFinalAnswerFromStateObservations() {

        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader promptTemplateLoader =
                mock(PromptTemplateLoader.class);

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .build();

        AgentObservation observation = AgentObservation.builder()
                .actionType(AgentActionType.CALL_MCP_TOOL)
                .success(true)
                .content("观察结果")
                .build();

        AgentStep step = AgentStep.builder()
                .stepIndex(0)
                .observation(observation)
                .build();

        Instant deadlineAt = Instant.parse("2026-07-20T12:00:00Z");
        AgentState state = AgentState.builder()
                .originalQuestion("原始问题")
                .steps(List.of(step))
                .deadlineAt(deadlineAt)
                .build();

        String expectedObservations = """
                Step 0
                actionType: CALL_MCP_TOOL
                success: true
                content: 观察结果
                errorMessage: 无
                """.trim();

        Map<String, String> expectedSlots = Map.of(
                "original_question", "原始问题",
                "observations", expectedObservations
        );

        when(promptTemplateLoader.render(
                "prompt/agent-final-answer.st",
                expectedSlots
        )).thenReturn("渲染后的 Prompt");

        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn("  最终回答  ");

        AgentObservation result =
                new FinalAnswerActionHandler(llmService, promptTemplateLoader)
                        .execute(action, state);

        assertEquals(AgentActionType.FINAL_ANSWER, result.getActionType());
        assertTrue(result.isSuccess());
        assertEquals("最终回答", result.getContent());
        assertNull(result.getErrorMessage());

        assertNull(state.getStopReason());
        assertNull(state.getFinalAnswer());

        verify(promptTemplateLoader).render(
                "prompt/agent-final-answer.st",
                expectedSlots
        );

        ArgumentCaptor<ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(requestCaptor.capture());
        assertEquals(
                deadlineAt,
                requestCaptor.getValue().getDeadlineAt()
        );
    }

    @Test
    void shouldRejectUnsupportedActionType() {
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader promptTemplateLoader =
                mock(PromptTemplateLoader.class);

        FinalAnswerActionHandler handler =
                new FinalAnswerActionHandler(llmService, promptTemplateLoader);

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .build();

        AgentState state = AgentState.builder()
                .originalQuestion("原始问题")
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> handler.execute(action, state)
        );

        assertEquals(
                "Unsupported action type: CALL_MCP_TOOL",
                exception.getMessage()
        );
        verifyNoInteractions(promptTemplateLoader, llmService);
    }

    @Test
    void shouldRejectBlankOriginalQuestion() {
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader promptTemplateLoader =
                mock(PromptTemplateLoader.class);

        FinalAnswerActionHandler handler =
                new FinalAnswerActionHandler(llmService, promptTemplateLoader);

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .build();

        AgentState state = AgentState.builder()
                .originalQuestion("   ")
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> handler.execute(action, state)
        );

        assertEquals(
                "Original question must be a non-blank string",
                exception.getMessage()
        );
        verifyNoInteractions(promptTemplateLoader, llmService);
    }

    @Test
    void shouldRejectBlankLlmAnswer() {
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader promptTemplateLoader =
                mock(PromptTemplateLoader.class);

        Map<String, String> expectedSlots = Map.of(
                "original_question", "原始问题",
                "observations", "无历史 Observation"
        );

        when(promptTemplateLoader.render(
                "prompt/agent-final-answer.st",
                expectedSlots
        )).thenReturn("渲染后的 Prompt");

        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn("   ");

        FinalAnswerActionHandler handler =
                new FinalAnswerActionHandler(llmService, promptTemplateLoader);

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .build();

        AgentState state = AgentState.builder()
                .originalQuestion("原始问题")
                .build();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> handler.execute(action, state)
        );

        assertEquals(
                "FINAL_ANSWER LLM returned a blank answer",
                exception.getMessage()
        );
        verify(promptTemplateLoader).render(
                "prompt/agent-final-answer.st",
                expectedSlots
        );
        verify(llmService).chat(any(ChatRequest.class));
    }

    @Test
    void shouldIncludeFailedObservationInPrompt() {
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader promptTemplateLoader =
                mock(PromptTemplateLoader.class);

        AgentObservation failedObservation = AgentObservation.builder()
                .actionType(AgentActionType.CALL_MCP_TOOL)
                .success(false)
                .content("")
                .errorMessage("查询服务超时")
                .build();

        AgentStep step = AgentStep.builder()
                .stepIndex(1)
                .observation(failedObservation)
                .build();

        AgentState state = AgentState.builder()
                .originalQuestion("分析销售额")
                .steps(List.of(step))
                .build();

        String expectedObservations = """
                Step 1
                actionType: CALL_MCP_TOOL
                success: false
                content: 无
                errorMessage: 查询服务超时
                """.trim();

        Map<String, String> expectedSlots = Map.of(
                "original_question", "分析销售额",
                "observations", expectedObservations
        );

        when(promptTemplateLoader.render(
                "prompt/agent-final-answer.st",
                expectedSlots
        )).thenReturn("渲染后的 Prompt");

        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn("部分数据暂时无法获取");

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .build();

        AgentObservation result = new FinalAnswerActionHandler(
                llmService,
                promptTemplateLoader
        ).execute(action, state);

        assertTrue(result.isSuccess());
        assertEquals(
                "部分数据暂时无法获取",
                result.getContent()
        );
        verify(promptTemplateLoader).render(
                "prompt/agent-final-answer.st",
                expectedSlots
        );
        verify(llmService).chat(any(ChatRequest.class));
    }

    @Test
    void shouldPrependHistorySnapshotToFinalAnswerPrompt() {
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader promptTemplateLoader =
                mock(PromptTemplateLoader.class);
        when(promptTemplateLoader.render(
                eq("prompt/agent-final-answer.st"),
                anyMap()
        )).thenReturn("rendered final prompt");
        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn("final answer");

        AgentState state = AgentState.builder()
                .originalQuestion("那它适合 IO 场景吗？")
                .historySnapshot(List.of(
                        ChatMessage.user("介绍一下 Java 虚拟线程"),
                        ChatMessage.assistant("虚拟线程是轻量级线程")
                ))
                .build();
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .build();

        new FinalAnswerActionHandler(
                llmService,
                promptTemplateLoader
        ).execute(action, state);

        ArgumentCaptor<ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(requestCaptor.capture());

        List<ChatMessage> messages =
                requestCaptor.getValue().getMessages();
        assertAll(
                () -> assertEquals(3, messages.size()),
                () -> assertEquals(
                        "介绍一下 Java 虚拟线程",
                        messages.get(0).getContent()
                ),
                () -> assertEquals(
                        ChatMessage.Role.ASSISTANT,
                        messages.get(1).getRole()
                ),
                () -> assertEquals(
                        "虚拟线程是轻量级线程",
                        messages.get(1).getContent()
                ),
                () -> assertEquals(
                        ChatMessage.Role.USER,
                        messages.get(2).getRole()
                ),
                () -> assertEquals(
                        "rendered final prompt",
                        messages.get(2).getContent()
                )
        );
    }

}
