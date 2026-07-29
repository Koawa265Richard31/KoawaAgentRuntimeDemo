package com.koawa.agent.agent.planner;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentFailureType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStep;
import com.koawa.agent.agent.exception.AgentFailureException;
import com.koawa.agent.agent.parser.AgentActionParser;
import com.koawa.agent.framework.convention.ChatMessage;
import com.koawa.agent.framework.convention.ChatRequest;
import com.koawa.agent.infra.chat.LLMService;
import com.koawa.agent.agent.mcp.McpToolRegistry;
import com.koawa.agent.agent.prompt.PromptTemplateLoader;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LlmAgentPlannerTest {

    private static final String PROMPT_PATH = "prompt/agent-planner.st";
    private static final String RENDERED_PROMPT = "rendered planner prompt";

    private LLMService llmService;
    private PromptTemplateLoader promptTemplateLoader;
    private AgentActionParser actionParser;
    private McpToolRegistry toolRegistry;
    private LlmAgentPlanner planner;
    private Map<String, String> renderedSlots;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        promptTemplateLoader = mock(PromptTemplateLoader.class);
        actionParser = mock(AgentActionParser.class);
        toolRegistry = mock(McpToolRegistry.class);

        when(toolRegistry.listAllTools()).thenReturn(List.of());
        when(promptTemplateLoader.render(eq(PROMPT_PATH), anyMap()))
                .thenAnswer(invocation -> {
                    renderedSlots = invocation.getArgument(1);
                    return RENDERED_PROMPT;
                });

        planner = new LlmAgentPlanner(
                llmService,
                actionParser,
                new AgentRequestAssembler(promptTemplateLoader),
                toolRegistry
        );
    }

    @Test
    void shouldParseLlmResponseIntoAction() {
        String rawAction = """
                {
                  "type": "CALL_MCP_TOOL",
                  "thought": "先调用检索工具",
                  "arguments": {
                    "toolId": "knowledge.search",
                    "params": {"query": "退款规则"}
                  }
                }
                """;

        AgentAction expectedAction = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .thought("先调用检索工具")
                .arguments(Map.of(
                        "toolId", "knowledge.search",
                        "params", Map.of("query", "退款规则")
                ))
                .build();

        stubPlannerResponse(rawAction, expectedAction);

        Instant deadlineAt = Instant.parse("2026-07-20T12:00:00Z");
        AgentState state = state(" 如何申请退款？ ");
        state.setDeadlineAt(deadlineAt);

        AgentAction result = planner.plan(state);

        assertSame(expectedAction, result);
        assertNotNull(renderedSlots);
        assertAll(
                () -> assertEquals(
                        "如何申请退款？",
                        renderedSlots.get("original_question")
                ),
                () -> assertEquals("0", renderedSlots.get("current_step")),
                () -> assertEquals("3", renderedSlots.get("max_steps")),
                () -> assertEquals("无历史步骤", renderedSlots.get("steps")),
                () -> assertEquals("无可用 MCP 工具", renderedSlots.get("tools")),
                () -> assertEquals(
                        "无规划恢复信息",
                        renderedSlots.get("recovery_context")
                )
        );

        ArgumentCaptor<ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(requestCaptor.capture());

        ChatRequest request = requestCaptor.getValue();
        assertAll(
                () -> assertEquals(1, request.getMessages().size()),
                () -> assertEquals(
                        RENDERED_PROMPT,
                        request.getMessages().get(0).getContent()
                ),
                () -> assertEquals(0.1D, request.getTemperature()),
                () -> assertEquals(Boolean.FALSE, request.getThinking()),
                () -> assertEquals(deadlineAt, request.getDeadlineAt())
        );
        verify(actionParser).parse(rawAction);
    }

    @Test
    void shouldIncludePlanningRecoveryContextInPrompt() {
        AgentAction expectedAction = stubFinalAnswerResponse();
        AgentState state = state("test question");
        state.setPlanningRecoveryAttempts(1);
        state.setFailureType(AgentFailureType.INVALID_ACTION_RESPONSE);

        AgentAction result = planner.plan(state);

        assertSame(expectedAction, result);
        String recoveryContext = renderedSlots.get("recovery_context");
        assertAll(
                () -> assertTrue(recoveryContext.contains(
                        "planningRetryAttempt: 1"
                )),
                () -> assertTrue(recoveryContext.contains(
                        "failureType: INVALID_ACTION_RESPONSE"
                )),
                () -> assertTrue(recoveryContext.contains(
                        "请修正 JSON 结构、Action 类型和 arguments"
                ))
        );
    }

    @Test
    void shouldIncludeFailedObservationInPrompt() {
        AgentAction expectedAction = stubFinalAnswerResponse();

        AgentAction failedAction = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .arguments(Map.of(
                        "toolId", "weather",
                        "params", Map.of("city", "上海")
                ))
                .build();

        AgentObservation failedObservation = AgentObservation.builder()
                .actionType(AgentActionType.CALL_MCP_TOOL)
                .success(false)
                .content(" ")
                .errorMessage("MCP tool execution failed: timeout")
                .build();

        AgentStep failedStep = AgentStep.builder()
                .stepIndex(0)
                .action(failedAction)
                .observation(failedObservation)
                .build();

        AgentState state = AgentState.builder()
                .originalQuestion("上海天气怎么样？")
                .currentStep(1)
                .maxSteps(3)
                .steps(List.of(failedStep))
                .build();

        AgentAction result = planner.plan(state);

        assertSame(expectedAction, result);
        String steps = renderedSlots.get("steps");
        assertAll(
                () -> assertTrue(steps.contains("Step 0")),
                () -> assertTrue(steps.contains("actionType: CALL_MCP_TOOL")),
                () -> assertTrue(steps.contains("\"toolId\":\"weather\"")),
                () -> assertTrue(steps.contains("observationSuccess: false")),
                () -> assertTrue(steps.contains("observationContent: 无")),
                () -> assertTrue(steps.contains(
                        "observationError: MCP tool execution failed: timeout"
                ))
        );
    }

    @Test
    void shouldIncludeMcpToolDefinitionInPrompt() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", Map.of(
                "type", "string",
                "description", "城市名称"
        ));

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                properties,
                List.of("city"),
                null,
                null,
                null
        );

        McpSchema.Tool weatherTool = McpSchema.Tool.builder()
                .name("weather")
                .description("查询城市天气")
                .inputSchema(inputSchema)
                .build();

        when(toolRegistry.listAllTools()).thenReturn(List.of(weatherTool));
        stubFinalAnswerResponse();

        planner.plan(state("上海天气怎么样？"));

        String tools = renderedSlots.get("tools");
        assertAll(
                () -> assertTrue(tools.contains("Tool 1")),
                () -> assertTrue(tools.contains("toolId: weather")),
                () -> assertTrue(tools.contains("description: 查询城市天气")),
                () -> assertTrue(tools.contains("inputSchema:")),
                () -> assertTrue(tools.contains("\"city\"")),
                () -> assertTrue(tools.contains("城市名称"))
        );
    }

    @Test
    void shouldPrependHistorySnapshotToPlannerPrompt() {
        stubFinalAnswerResponse();
        AgentState state = state("那它适合 IO 场景吗？");
        state.setHistorySnapshot(List.of(
                ChatMessage.user("介绍一下 Java 虚拟线程"),
                ChatMessage.assistant("虚拟线程是轻量级线程")
        ));

        planner.plan(state);

        ArgumentCaptor<ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(requestCaptor.capture());

        List<ChatMessage> messages =
                requestCaptor.getValue().getMessages();
        assertAll(
                () -> assertEquals(3, messages.size()),
                () -> assertEquals(
                        ChatMessage.Role.USER,
                        messages.get(0).getRole()
                ),
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
                        RENDERED_PROMPT,
                        messages.get(2).getContent()
                )
        );
    }

    @Test
    void shouldRejectBlankOriginalQuestion() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(state(" "))
        );

        assertEquals(
                "Original question must be a non-blank string",
                exception.getMessage()
        );
        verify(toolRegistry).listAllTools();
        verifyNoInteractions(
                promptTemplateLoader,
                llmService,
                actionParser
        );
    }

    @Test
    void shouldRejectBlankLlmResponse() {
        String rawAction = " ";
        when(llmService.chat(any(ChatRequest.class))).thenReturn(rawAction);

        AgentFailureException exception = assertThrows(
                AgentFailureException.class,
                () -> planner.plan(state("测试问题"))
        );

        assertAll(
                () -> assertEquals(
                        AgentFailureType.EMPTY_MODEL_RESPONSE,
                        exception.getFailureType()
                ),
                () -> assertEquals(
                        "Agent planner LLM returned a blank action",
                        exception.getMessage()
                ),
                () -> assertNull(exception.getCause())
        );
        verifyNoInteractions(actionParser);
    }

    @Test
    void shouldClassifyParserFailure() {
        String rawAction = "not-json";
        IllegalArgumentException parserFailure =
                new IllegalArgumentException("Agent action is invalid JSON");

        when(llmService.chat(any(ChatRequest.class))).thenReturn(rawAction);
        when(actionParser.parse(rawAction)).thenThrow(parserFailure);

        AgentFailureException result = assertThrows(
                AgentFailureException.class,
                () -> planner.plan(state("测试问题"))
        );

        assertAll(
                () -> assertEquals(
                        AgentFailureType.INVALID_ACTION_RESPONSE,
                        result.getFailureType()
                ),
                () -> assertEquals(parserFailure.getMessage(), result.getMessage()),
                () -> assertSame(parserFailure, result.getCause())
        );
    }

    @Test
    void shouldClassifyModelCallFailureAndPreserveCause() {
        IllegalStateException modelFailure =
                new IllegalStateException("model unavailable");
        when(llmService.chat(any(ChatRequest.class))).thenThrow(modelFailure);

        AgentFailureException result = assertThrows(
                AgentFailureException.class,
                () -> planner.plan(state("测试问题"))
        );

        assertAll(
                () -> assertEquals(
                        AgentFailureType.MODEL_CALL_FAILED,
                        result.getFailureType()
                ),
                () -> assertEquals(modelFailure.getMessage(), result.getMessage()),
                () -> assertSame(modelFailure, result.getCause())
        );
        verifyNoInteractions(actionParser);
    }

    private AgentState state(String question) {
        return AgentState.builder()
                .originalQuestion(question)
                .currentStep(0)
                .maxSteps(3)
                .build();
    }

    private AgentAction stubFinalAnswerResponse() {
        String rawAction = """
                {
                  "type": "FINAL_ANSWER",
                  "thought": "已有信息足够",
                  "arguments": {}
                }
                """;

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .thought("已有信息足够")
                .arguments(Map.of())
                .build();

        stubPlannerResponse(rawAction, action);
        return action;
    }

    private void stubPlannerResponse(
            String rawAction,
            AgentAction parsedAction
    ) {
        when(llmService.chat(any(ChatRequest.class))).thenReturn(rawAction);
        when(actionParser.parse(rawAction)).thenReturn(parsedAction);
    }
}
