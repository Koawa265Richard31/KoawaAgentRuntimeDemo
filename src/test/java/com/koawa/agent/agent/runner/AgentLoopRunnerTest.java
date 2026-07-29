package com.koawa.agent.agent.runner;

import com.koawa.agent.agent.domain.*;
import com.koawa.agent.agent.event.AgentEventSink;
import com.koawa.agent.agent.exception.AgentFailureException;
import com.koawa.agent.agent.executor.AgentActionExecutor;
import com.koawa.agent.agent.executor.RoutingAgentActionExecutor;
import com.koawa.agent.agent.executor.handler.AskClarificationActionHandler;
import com.koawa.agent.agent.executor.handler.FinalAnswerActionHandler;
import com.koawa.agent.agent.planner.AgentPlanner;
import com.koawa.agent.agent.recovery.AgentRecoveryDecision;
import com.koawa.agent.agent.recovery.AgentRecoveryPolicy;
import com.koawa.agent.agent.recovery.DefaultAgentRecoveryPolicy;
import com.koawa.agent.framework.convention.ChatRequest;
import com.koawa.agent.infra.chat.LLMService;
import com.koawa.agent.agent.planner.ScriptedAgentPlanner;
import com.koawa.agent.agent.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentLoopRunnerTest {
    private static final AgentRecoveryPolicy NO_RECOVERY =
            failureType -> AgentRecoveryDecision.STOP;
    private static final Instant START =
            Instant.parse("2026-07-20T00:00:00Z");
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(10);

    private AgentAction action(AgentActionType type) {
        return AgentAction.builder()
                .type(type)
                .thought("test")
                .build();
    }

    private AgentObservation observation(
            AgentActionType type,
            String content
    ) {
        return AgentObservation.builder()
                .actionType(type)
                .success(true)
                .content(content)
                .build();
    }

    private AgentState state(int maxSteps) {
        return AgentState.builder()
                .taskId("task-1")
                .currentStep(0)
                .maxSteps(maxSteps)
                .build();
    }

    @Test
    void shouldRunUntilFinalAnswer() {
        AgentAction toolCall = action(AgentActionType.CALL_MCP_TOOL);
        AgentAction finish = action(AgentActionType.FINAL_ANSWER);

        ScriptedAgentPlanner planner = new ScriptedAgentPlanner(List.of(toolCall, finish));
        AgentActionExecutor executor = (action, state) ->
                observation(
                        action.getType(),
                        action.getType() == AgentActionType.FINAL_ANSWER
                                ? "最终回答"
                                : "工具结果"
                );

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                NO_RECOVERY
        ).run(state(5));

        assertEquals(2, result.getCurrentStep());
        assertEquals(2, result.getSteps().size());
        assertEquals(AgentStopReason.FINAL_ANSWER, result.getStopReason());
        assertEquals("最终回答", result.getFinalAnswer());
    }

    @Test
    void shouldStopAtMaximumSteps() {
        List<AgentEvent> events = new ArrayList<>();

        ScriptedAgentPlanner planner = new ScriptedAgentPlanner(
                List.of(
                        action(AgentActionType.CALL_MCP_TOOL),
                        action(AgentActionType.CALL_MCP_TOOL)
                )
        );

        AgentActionExecutor executor = (action, state) ->
                observation(action.getType(), "工具结果");

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                events::add,
                AgentCancellationChecker.NEVER_CANCELLED,
                NO_RECOVERY
        ).run(state(2));

        AgentEvent completed = events.get(events.size() - 1);
        assertEquals(2, result.getCurrentStep());
        assertEquals(2, result.getSteps().size());
        assertEquals(AgentStopReason.MAX_STEPS, result.getStopReason());
        assertNull(result.getFinalAnswer());
        assertAll(
                () -> assertEquals(
                        AgentEventType.TURN_COMPLETED,
                        completed.type()
                ),
                () -> assertEquals(
                        AgentStopReason.MAX_STEPS,
                        completed.stopReason()
                ),
                () -> assertEquals("task-1", completed.taskId()),
                () -> assertNull(completed.content())
        );
    }

    @Test
    void shouldStoreRuntimeErrorInState() {
        List<AgentEvent> events = new ArrayList<>();
        ScriptedAgentPlanner planner = new ScriptedAgentPlanner(List.of(
                action(AgentActionType.CALL_MCP_TOOL)
        ));

        AgentActionExecutor executor = (action, state) -> {
            throw new IllegalStateException("executor failed");
        };

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                events::add,
                AgentCancellationChecker.NEVER_CANCELLED,
                NO_RECOVERY
        ).run(state(3));

        AgentEvent failed = events.get(events.size() - 1);
        assertEquals(0, result.getCurrentStep());
        assertTrue(result.getSteps().isEmpty());
        assertEquals(AgentStopReason.ERROR, result.getStopReason());
        assertEquals(
                AgentFailureType.ACTION_EXECUTION_FAILED,
                result.getFailureType()
        );
        assertEquals("executor failed", result.getErrorMessage());
        assertAll(
                () -> assertEquals(AgentEventType.TURN_FAILED, failed.type()),
                () -> assertEquals(Boolean.FALSE, failed.success()),
                () -> assertEquals(
                        AgentStopReason.ERROR,
                        failed.stopReason()
                ),
                () -> assertEquals("task-1", failed.taskId()),
                () -> assertEquals("executor failed", failed.errorMessage())
        );
    }

    @Test
    void shouldRecoverWhenFirstPlanningAttemptReturnsInvalidAction() {
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        AgentFailureException plannerFailure = new AgentFailureException(
                AgentFailureType.INVALID_ACTION_RESPONSE,
                "Agent action is invalid JSON"
        );
        AgentAction finalAnswer = action(AgentActionType.FINAL_ANSWER);
        when(planner.plan(any(AgentState.class)))
                .thenThrow(plannerFailure)
                .thenReturn(finalAnswer);
        when(executor.execute(eq(finalAnswer), any(AgentState.class)))
                .thenReturn(observation(
                        AgentActionType.FINAL_ANSWER,
                        "final answer"
                ));

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                new DefaultAgentRecoveryPolicy()
        ).run(state(3));

        assertAll(
                () -> assertEquals(1, result.getCurrentStep()),
                () -> assertEquals(1, result.getSteps().size()),
                () -> assertEquals(
                        AgentStopReason.FINAL_ANSWER,
                        result.getStopReason()
                ),
                () -> assertEquals(1, result.getPlanningRecoveryAttempts()),
                () -> assertNull(result.getFailureType()),
                () -> assertNull(result.getErrorMessage())
        );
        verify(planner, times(2)).plan(any(AgentState.class));
        verify(executor).execute(eq(finalAnswer), any(AgentState.class));
    }

    @Test
    void shouldStopWhenPlanningRecoveryBudgetIsExhausted() {
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        AgentFailureException plannerFailure = new AgentFailureException(
                AgentFailureType.INVALID_ACTION_RESPONSE,
                "Agent action is invalid JSON"
        );
        when(planner.plan(any(AgentState.class)))
                .thenThrow(plannerFailure, plannerFailure);

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                new DefaultAgentRecoveryPolicy()
        ).run(state(3));

        assertAll(
                () -> assertEquals(0, result.getCurrentStep()),
                () -> assertTrue(result.getSteps().isEmpty()),
                () -> assertEquals(
                        AgentStopReason.ERROR,
                        result.getStopReason()
                ),
                () -> assertEquals(
                        AgentFailureType.INVALID_ACTION_RESPONSE,
                        result.getFailureType()
                ),
                () -> assertEquals(
                        plannerFailure.getMessage(),
                        result.getErrorMessage()
                ),
                () -> assertEquals(1, result.getPlanningRecoveryAttempts())
        );
        verify(planner, times(2)).plan(any(AgentState.class));
        verifyNoInteractions(executor);
    }

    @Test
    void shouldPublishStepEventsInOrder() {
        List<AgentEvent> events = new ArrayList<>();
        AgentEventSink eventSink = events::add;

        AgentAction finalAnswer = action(AgentActionType.FINAL_ANSWER);
        AgentPlanner planner = state -> {
            assertEquals(
                    List.of(
                            AgentEventType.TURN_STARTED,
                            AgentEventType.STEP_STARTED
                    ),
                    events.stream().map(AgentEvent::type).toList()
            );
            return finalAnswer;
        };
        AgentActionExecutor executor = (action, state) -> {
            assertEquals(
                    List.of(
                            AgentEventType.TURN_STARTED,
                            AgentEventType.STEP_STARTED,
                            AgentEventType.ACTION_PLANNED
                    ),
                    events.stream().map(AgentEvent::type).toList()
            );
            return observation(action.getType(), "最终回答");
        };

        AgentState state = AgentState.builder()
                .conversationId("conversation-1")
                .taskId("task-1")
                .currentStep(0)
                .maxSteps(2)
                .build();

        new AgentLoopRunner(
                planner,
                executor,
                eventSink,
                AgentCancellationChecker.NEVER_CANCELLED,
                NO_RECOVERY
        ).run(state);

        AgentEvent turnStarted = events.get(0);
        AgentEvent stepStarted = events.get(1);
        AgentEvent actionPlanned = events.get(2);
        AgentEvent observationReceived = events.get(3);
        AgentEvent turnCompleted = events.get(4);
        assertAll(
                () -> assertEquals(
                        List.of(
                                AgentEventType.TURN_STARTED,
                                AgentEventType.STEP_STARTED,
                                AgentEventType.ACTION_PLANNED,
                                AgentEventType.OBSERVATION_RECEIVED,
                                AgentEventType.TURN_COMPLETED
                        ),
                        events.stream().map(AgentEvent::type).toList()
                ),
                () -> assertTrue(events.stream().allMatch(
                        event -> "task-1".equals(event.taskId())
                )),
                () -> assertEquals(
                        "conversation-1",
                        turnStarted.conversationId()
                ),
                () -> assertEquals(0, turnStarted.stepIndex()),
                () -> assertEquals(
                        "conversation-1",
                        stepStarted.conversationId()
                ),
                () -> assertEquals(0, stepStarted.stepIndex()),
                () -> assertNull(stepStarted.actionType()),
                () -> assertNull(stepStarted.success()),
                () -> assertNull(stepStarted.stopReason()),
                () -> assertEquals(
                        AgentActionType.FINAL_ANSWER,
                        actionPlanned.actionType()
                ),
                () -> assertEquals(
                        AgentActionType.FINAL_ANSWER,
                        observationReceived.actionType()
                ),
                () -> assertEquals(Boolean.TRUE, observationReceived.success()),
                () -> assertEquals("最终回答", observationReceived.content()),
                () -> assertNull(observationReceived.errorMessage()),
                () -> assertEquals(
                        AgentStopReason.FINAL_ANSWER,
                        turnCompleted.stopReason()
                ),
                () -> assertEquals("最终回答", turnCompleted.content())
        );
    }

    @Test
    void shouldContinueWhenEventSinkFails() {
        AgentAction finalAnswer = action(AgentActionType.FINAL_ANSWER);
        AgentPlanner planner = state -> finalAnswer;
        AgentActionExecutor executor = (action, state) ->
                observation(action.getType(), "最终回答");
        AgentEventSink failingSink = event -> {
            throw new IllegalStateException("event sink failed");
        };

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                failingSink,
                AgentCancellationChecker.NEVER_CANCELLED,
                NO_RECOVERY
        ).run(state(2));

        assertAll(
                () -> assertEquals(1, result.getCurrentStep()),
                () -> assertEquals(1, result.getSteps().size()),
                () -> assertEquals(
                        AgentStopReason.FINAL_ANSWER,
                        result.getStopReason()
                ),
                () -> assertEquals("最终回答", result.getFinalAnswer()),
                () -> assertNull(result.getErrorMessage())
        );
    }

    @Test
    void shouldStopForClarificationThroughRoutingExecutor() {

        List<AgentAction> actions = List.of(
                AgentAction.builder()
                        .type(AgentActionType.ASK_CLARIFICATION)
                        .arguments(Map.of(
                                "question", "澄清问题"
                        ))
                        .build()
        );

        ScriptedAgentPlanner planner = new ScriptedAgentPlanner(actions);

        AskClarificationActionHandler handler = new AskClarificationActionHandler();

        RoutingAgentActionExecutor executor = new RoutingAgentActionExecutor(
                List.of(handler)
        );

        AgentLoopRunner runner = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                NO_RECOVERY
        );

        AgentState state = AgentState.builder()
                .currentStep(0)
                .maxSteps(2)
                .build();

        AgentState resultState = runner.run(state);

        assertEquals(1, resultState.getCurrentStep());
        assertEquals(1, resultState.getSteps().size());
        assertEquals(AgentStopReason.ASK_CLARIFICATION, resultState.getStopReason());
        assertEquals("澄清问题", resultState.getFinalAnswer());

    }

    @Test
    void shouldStopForFinalAnswerThroughRoutingExecutor() {
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader promptTemplateLoader =
                mock(PromptTemplateLoader.class);

        Map<String, String> expectedSlots = Map.of(
                "original_question", "用户问题",
                "observations", "无历史 Observation"
        );

        when(promptTemplateLoader.render(
                "prompt/agent-final-answer.st",
                expectedSlots
        )).thenReturn("渲染后的 Prompt");

        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn("最终回答");

        AgentAction finalAnswer = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .build();

        ScriptedAgentPlanner planner =
                new ScriptedAgentPlanner(List.of(finalAnswer));

        RoutingAgentActionExecutor executor =
                new RoutingAgentActionExecutor(List.of(
                        new FinalAnswerActionHandler(
                                llmService,
                                promptTemplateLoader
                        )
                ));

        AgentState state = AgentState.builder()
                .originalQuestion("用户问题")
                .currentStep(0)
                .maxSteps(2)
                .build();

        AgentState result =
                new AgentLoopRunner(
                        planner,
                        executor,
                        AgentEventSink.NOOP,
                        AgentCancellationChecker.NEVER_CANCELLED,
                        NO_RECOVERY
                ).run(state);

        assertEquals(1, result.getCurrentStep());
        assertEquals(1, result.getSteps().size());
        assertEquals(AgentStopReason.FINAL_ANSWER, result.getStopReason());
        assertEquals("最终回答", result.getFinalAnswer());
        assertNull(result.getErrorMessage());

        verify(promptTemplateLoader).render(
                "prompt/agent-final-answer.st",
                expectedSlots
        );
        verify(llmService).chat(any(ChatRequest.class));
    }

    @Test
    void shouldCancelBeforePlanning() {
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        List<AgentEvent> events = new ArrayList<>();
        AgentCancellationChecker cancellationChecker = taskId -> true;

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                events::add,
                cancellationChecker,
                NO_RECOVERY
        ).run(state(2));

        assertAll(
                () -> assertEquals(0, result.getCurrentStep()),
                () -> assertTrue(result.getSteps().isEmpty()),
                () -> assertEquals(
                        AgentStopReason.CANCELLED,
                        result.getStopReason()
                ),
                () -> assertEquals(
                        List.of(
                                AgentEventType.TURN_STARTED,
                                AgentEventType.TURN_COMPLETED
                        ),
                        events.stream().map(AgentEvent::type).toList()
                ),
                () -> assertEquals(
                        AgentStopReason.CANCELLED,
                        events.get(1).stopReason()
                )
        );
        verifyNoInteractions(planner, executor);
    }

    @Test
    void shouldCancelAfterPlanningWithoutExecutingAction() {
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        AgentAction plannedAction = action(AgentActionType.CALL_MCP_TOOL);
        AtomicInteger cancellationChecks = new AtomicInteger();
        AgentCancellationChecker cancellationChecker = taskId ->
                cancellationChecks.incrementAndGet() >= 2;
        when(planner.plan(any(AgentState.class))).thenReturn(plannedAction);

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                cancellationChecker,
                NO_RECOVERY
        ).run(state(2));

        assertAll(
                () -> assertEquals(0, result.getCurrentStep()),
                () -> assertTrue(result.getSteps().isEmpty()),
                () -> assertEquals(
                        AgentStopReason.CANCELLED,
                        result.getStopReason()
                ),
                () -> assertEquals(2, cancellationChecks.get())
        );
        verify(planner).plan(any(AgentState.class));
        verifyNoInteractions(executor);
    }

    @Test
    void shouldTimeoutBeforeFirstPlanningAttempt() {
        Instant deadline = START.plus(TURN_TIMEOUT);
        MutableClock clock = new MutableClock(deadline);
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        AgentState state = state(2);
        state.setDeadlineAt(deadline);

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                NO_RECOVERY,
                clock
        ).run(state);

        assertAll(
                () -> assertEquals(0, result.getCurrentStep()),
                () -> assertTrue(result.getSteps().isEmpty()),
                () -> assertEquals(
                        AgentStopReason.TIMEOUT,
                        result.getStopReason()
                ),
                () -> assertNull(result.getFailureType()),
                () -> assertNull(result.getErrorMessage())
        );
        verifyNoInteractions(planner, executor);
    }

    @Test
    void shouldTimeoutAfterPlanningBeforeExecutingAction() {
        Instant deadline = START.plus(TURN_TIMEOUT);
        MutableClock clock = new MutableClock(START);
        AgentAction plannedAction = action(AgentActionType.CALL_MCP_TOOL);
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        AgentState state = state(2);
        state.setDeadlineAt(deadline);
        when(planner.plan(any(AgentState.class))).thenAnswer(invocation -> {
            clock.advance(TURN_TIMEOUT);
            return plannedAction;
        });

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                NO_RECOVERY,
                clock
        ).run(state);

        assertAll(
                () -> assertEquals(0, result.getCurrentStep()),
                () -> assertTrue(result.getSteps().isEmpty()),
                () -> assertEquals(
                        AgentStopReason.TIMEOUT,
                        result.getStopReason()
                )
        );
        verify(planner).plan(any(AgentState.class));
        verifyNoInteractions(executor);
    }

    @Test
    void shouldRecordObservationBeforeTimingOutAfterExecution() {
        Instant deadline = START.plus(TURN_TIMEOUT);
        MutableClock clock = new MutableClock(START);
        AgentAction plannedAction = action(AgentActionType.CALL_MCP_TOOL);
        AgentObservation completedObservation = observation(
                AgentActionType.CALL_MCP_TOOL,
                "tool result"
        );
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        AgentState state = state(2);
        state.setDeadlineAt(deadline);
        when(planner.plan(any(AgentState.class))).thenReturn(plannedAction);
        when(executor.execute(
                eq(plannedAction),
                any(AgentState.class)
        )).thenAnswer(invocation -> {
            clock.advance(TURN_TIMEOUT);
            return completedObservation;
        });

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                NO_RECOVERY,
                clock
        ).run(state);

        assertAll(
                () -> assertEquals(1, result.getCurrentStep()),
                () -> assertEquals(1, result.getSteps().size()),
                () -> assertSame(
                        completedObservation,
                        result.getSteps().get(0).getObservation()
                ),
                () -> assertEquals(
                        AgentStopReason.TIMEOUT,
                        result.getStopReason()
                )
        );
        verify(planner).plan(any(AgentState.class));
        verify(executor).execute(
                eq(plannedAction),
                any(AgentState.class)
        );
    }

    @Test
    void shouldTimeoutBeforePlanningRecoveryRetry() {
        Instant deadline = START.plus(TURN_TIMEOUT);
        MutableClock clock = new MutableClock(START);
        AgentFailureException plannerFailure = new AgentFailureException(
                AgentFailureType.INVALID_ACTION_RESPONSE,
                "Agent action is invalid JSON"
        );
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        AgentState state = state(2);
        state.setDeadlineAt(deadline);
        when(planner.plan(any(AgentState.class))).thenAnswer(invocation -> {
            clock.advance(TURN_TIMEOUT);
            throw plannerFailure;
        });

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                new DefaultAgentRecoveryPolicy(),
                clock
        ).run(state);

        assertAll(
                () -> assertEquals(0, result.getCurrentStep()),
                () -> assertTrue(result.getSteps().isEmpty()),
                () -> assertEquals(
                        AgentStopReason.TIMEOUT,
                        result.getStopReason()
                )
        );
        verify(planner).plan(any(AgentState.class));
        verifyNoInteractions(executor);
    }

    @Test
    void shouldPreferCancellationWhenCancelledAndTimedOut() {
        Instant deadline = START.plus(TURN_TIMEOUT);
        MutableClock clock = new MutableClock(deadline);
        AgentPlanner planner = mock(AgentPlanner.class);
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        AgentState state = state(2);
        state.setDeadlineAt(deadline);

        AgentState result = new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                taskId -> true,
                NO_RECOVERY,
                clock
        ).run(state);

        assertAll(
                () -> assertEquals(
                        AgentStopReason.CANCELLED,
                        result.getStopReason()
                ),
                () -> assertNull(result.getFailureType()),
                () -> assertNull(result.getErrorMessage())
        );
        verifyNoInteractions(planner, executor);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
