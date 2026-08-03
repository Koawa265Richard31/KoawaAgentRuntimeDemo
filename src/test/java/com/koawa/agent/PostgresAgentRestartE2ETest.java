package com.koawa.agent;

import com.koawa.agent.agent.checkpoint.resume.AgentResumeCommand;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeExecutionResult;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeExecutionService;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryResult;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.conversation.JdbcAgentConversationStore;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;
import com.koawa.agent.agent.runner.AgentLoopRunner;
import com.koawa.agent.agent.runtime.InMemoryAgentConversationStore;
import com.koawa.agent.agent.service.AgentChatFacade;
import com.koawa.agent.agent.service.AgentConversationHistoryLoader;
import com.koawa.agent.agent.service.AgentConversationStore;
import com.koawa.agent.framework.convention.ChatMessage;
import com.koawa.agent.framework.convention.ChatRequest;
import com.koawa.agent.infra.chat.LLMService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL evidence across three independently built Spring contexts.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresAgentRestartE2ETest {

    private static final Instant NOW =
            Instant.parse("2026-08-03T08:00:00Z");
    private static final String CONVERSATION_ID = "restart-conversation";
    private static final String USER_ID = "user-1";
    private static final String ASK_TASK_ID = "restart-ask-task";
    private static final String FOLLOW_UP_TASK_ID = "restart-follow-up-task";
    private static final String ORIGINAL_QUESTION =
            "Help me choose a repository";
    private static final String CLARIFICATION = "Which repository?";
    private static final String INTERRUPT_REPLY = "repository-alpha";
    private static final String RESUMED_ANSWER = "Use repository-alpha.";
    private static final String FOLLOW_UP_QUESTION = "What did we choose?";
    private static final String FOLLOW_UP_ANSWER =
            "We chose repository-alpha.";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void cleanDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .cleanDisabled(false)
                .load();
        flyway.clean();
    }

    @Test
    void shouldRecoverAskResumeFinalAndLoadHistoryAcrossRestarts() {
        DataSource firstDataSource;
        try (ConfigurableApplicationContext first = startContext(
                AgentActionType.ASK_CLARIFICATION,
                "unused"
        )) {
            assertRuntimeWiring(first);
            firstDataSource = first.getBean(DataSource.class);

            AgentState state = initialState();
            AgentCheckpointLifecycle lifecycle =
                    first.getBean(AgentCheckpointLifecycle.class);
            lifecycle.initialize(state);
            AgentState stopped = first.getBean(AgentLoopRunner.class)
                    .run(state);

            assertEquals(
                    AgentStopReason.ASK_CLARIFICATION,
                    stopped.getStopReason()
            );
            assertEquals(CLARIFICATION, stopped.getFinalAnswer());
            AgentTaskSnapshot running = load(first, ASK_TASK_ID);
            assertEquals(1, running.revision());
            assertEquals(AgentTaskStatus.RUNNING, running.status());
            assertEquals(1, running.nextStep());
            assertEquals(
                    AgentActionType.ASK_CLARIFICATION,
                    running.steps().get(0).actionType()
            );
            assertNull(running.pendingInterrupt());
            assertEquals(0L, count(first, "agent_conversation_head"));
            assertEquals(0L, count(first, "agent_conversation_turn"));

            ScriptedLlmService llm =
                    first.getBean(ScriptedLlmService.class);
            assertEquals(1, llm.plannerCallCount());
            assertEquals(0, llm.finalAnswerCallCount());

            // Deliberately omit lifecycle.completed(stopped): the context
            // closes with a persisted terminal Step and RUNNING task.
        }

        String interruptId;
        DataSource secondDataSource;
        try (ConfigurableApplicationContext second = startContext(
                AgentActionType.FINAL_ANSWER,
                RESUMED_ANSWER
        )) {
            assertRuntimeWiring(second);
            secondDataSource = second.getBean(DataSource.class);
            assertNotSame(firstDataSource, secondDataSource);

            AgentResumeExecutionService resumeService =
                    second.getBean(AgentResumeExecutionService.class);
            AgentResumeCommand recoveryCommand = new AgentResumeCommand(
                    ASK_TASK_ID,
                    1,
                    null,
                    null
            );
            AgentResumeExecutionResult.Recovered recovered =
                    assertInstanceOf(
                            AgentResumeExecutionResult.Recovered.class,
                            resumeService.resume(recoveryCommand)
                    );

            assertEquals(
                    AgentSnapshotRecoveryResult.Outcome
                            .TERMINAL_STEP_REPAIRED,
                    recovered.recovery().outcome()
            );
            AgentTaskSnapshot waiting = recovered.recovery().snapshot();
            assertEquals(2, waiting.revision());
            assertEquals(
                    AgentTaskStatus.WAITING_FOR_INPUT,
                    waiting.status()
            );
            assertEquals(CLARIFICATION, waiting.pendingInterrupt().prompt());
            interruptId = waiting.pendingInterrupt().interruptId();

            ScriptedLlmService llm =
                    second.getBean(ScriptedLlmService.class);
            assertEquals(0, llm.totalCallCount());
            assertEquals(1L, count(second, "agent_conversation_turn"));
            assertEquals(1L, headSequence(second));

            assertThrows(
                    CheckpointConflictException.class,
                    () -> resumeService.resume(recoveryCommand)
            );
            assertEquals(1L, count(second, "agent_conversation_turn"));
            assertEquals(1L, headSequence(second));

            AgentResumeExecutionResult.Executed executed =
                    assertInstanceOf(
                            AgentResumeExecutionResult.Executed.class,
                            resumeService.resume(new AgentResumeCommand(
                                    ASK_TASK_ID,
                                    2,
                                    interruptId,
                                    INTERRUPT_REPLY
                            ))
                    );
            assertEquals(
                    AgentStopReason.FINAL_ANSWER,
                    executed.runResult().stopReason()
            );
            assertEquals(RESUMED_ANSWER, executed.runResult().content());

            AgentTaskSnapshot completed = load(second, ASK_TASK_ID);
            assertEquals(5, completed.revision());
            assertEquals(AgentTaskStatus.COMPLETED, completed.status());
            assertEquals(2, completed.nextStep());
            assertEquals(2, completed.steps().size());
            assertEquals(1, llm.plannerCallCount());
            assertEquals(1, llm.finalAnswerCallCount());
            assertEquals(expectedAskTaskHistory(), history(second));
            assertEquals(2L, count(second, "agent_conversation_turn"));
            assertEquals(2L, headSequence(second));
        }

        try (ConfigurableApplicationContext third = startContext(
                AgentActionType.FINAL_ANSWER,
                FOLLOW_UP_ANSWER
        )) {
            assertRuntimeWiring(third);
            assertNotSame(
                    secondDataSource,
                    third.getBean(DataSource.class)
            );
            assertEquals(expectedAskTaskHistory(), history(third));

            AgentRunResult followUp = third.getBean(AgentChatFacade.class)
                    .chat(
                            FOLLOW_UP_QUESTION,
                            CONVERSATION_ID,
                            FOLLOW_UP_TASK_ID,
                            USER_ID
                    );
            assertEquals(AgentStopReason.FINAL_ANSWER, followUp.stopReason());
            assertEquals(FOLLOW_UP_ANSWER, followUp.content());

            ScriptedLlmService llm =
                    third.getBean(ScriptedLlmService.class);
            List<ChatMessage> plannerMessages = llm.plannerMessages(0);
            assertEquals(5, plannerMessages.size());
            assertEquals(
                    expectedAskTaskHistory(),
                    plannerMessages.subList(0, 4)
            );
            assertEquals(
                    ChatMessage.Role.USER,
                    plannerMessages.get(4).getRole()
            );
            assertTrue(
                    plannerMessages.get(4).getContent()
                            .contains(FOLLOW_UP_QUESTION)
            );

            AgentTaskSnapshot followUpSnapshot = load(
                    third,
                    FOLLOW_UP_TASK_ID
            );
            assertEquals(2, followUpSnapshot.revision());
            assertEquals(
                    AgentTaskStatus.COMPLETED,
                    followUpSnapshot.status()
            );
            assertEquals(1, followUpSnapshot.nextStep());
            assertEquals(
                    expectedAskTaskHistory(),
                    third.getBean(AgentTaskSnapshotMapper.class)
                            .toState(followUpSnapshot)
                            .getHistorySnapshot()
            );
            assertEquals(expectedCompleteHistory(), history(third));
            assertEquals(3L, count(third, "agent_conversation_turn"));
            assertEquals(3L, headSequence(third));
            assertEquals(expectedTurns(interruptId), loadTurns(third));
        }
    }

    private ConfigurableApplicationContext startContext(
            AgentActionType actionType,
            String finalAnswer
    ) {
        return new SpringApplicationBuilder(
                KoawaAgentApplication.class,
                RestartE2EConfiguration.class
        )
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url="
                                + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username="
                                + POSTGRES.getUsername(),
                        "--spring.datasource.password="
                                + POSTGRES.getPassword(),
                        "--spring.datasource.driver-class-name="
                                + "org.postgresql.Driver",
                        "--spring.flyway.enabled=true",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--agent.llm.api-key=test-key",
                        "--agent.runtime.max-steps=4",
                        "--agent.runtime.turn-timeout=1h",
                        "--agent.checkpoint.execution.lease-duration=10s",
                        "--agent.checkpoint.execution.renew-interval=2s",
                        "--test.e2e.action=" + actionType.name(),
                        "--test.e2e.final-answer=" + finalAnswer
                );
    }

    private AgentState initialState() {
        return AgentState.builder()
                .conversationId(CONVERSATION_ID)
                .taskId(ASK_TASK_ID)
                .userId(USER_ID)
                .originalQuestion(ORIGINAL_QUESTION)
                .currentStep(0)
                .maxSteps(4)
                .deadlineAt(NOW.plus(Duration.ofHours(1)))
                .historySnapshot(List.of())
                .build();
    }

    private void assertRuntimeWiring(ConfigurableApplicationContext context) {
        Map<String, AgentConversationStore> stores =
                context.getBeansOfType(AgentConversationStore.class);
        assertEquals(Set.of("agentConversationStore"), stores.keySet());
        AgentConversationStore store = stores.get("agentConversationStore");
        assertInstanceOf(JdbcAgentConversationStore.class, store);
        assertSame(
                store,
                context.getBean(AgentConversationHistoryLoader.class)
        );
        assertTrue(
                context.getBeansOfType(
                        InMemoryAgentConversationStore.class
                ).isEmpty()
        );
    }

    private AgentTaskSnapshot load(
            ConfigurableApplicationContext context,
            String taskId
    ) {
        return context.getBean(AgentCheckpointStore.class)
                .load(taskId)
                .orElseThrow();
    }

    private List<ChatMessage> history(
            ConfigurableApplicationContext context
    ) {
        return context.getBean(AgentConversationStore.class)
                .load(CONVERSATION_ID, USER_ID);
    }

    private long count(
            ConfigurableApplicationContext context,
            String table
    ) {
        Long count = context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT count(*) FROM " + table,
                Long.class
        );
        return count == null ? 0 : count;
    }

    private long headSequence(ConfigurableApplicationContext context) {
        Long sequence = context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT next_turn_sequence "
                        + "FROM agent_conversation_head",
                Long.class
        );
        return Objects.requireNonNull(sequence);
    }

    private List<PersistedTurn> loadTurns(
            ConfigurableApplicationContext context
    ) {
        return context.getBean(JdbcTemplate.class).query(
                "SELECT turn_sequence, task_id, terminal_step_index, "
                        + "input_type, source_interrupt_id, input_content, "
                        + "output_type, output_content "
                        + "FROM agent_conversation_turn "
                        + "ORDER BY turn_sequence",
                (resultSet, rowNumber) -> new PersistedTurn(
                        resultSet.getLong("turn_sequence"),
                        resultSet.getString("task_id"),
                        resultSet.getInt("terminal_step_index"),
                        resultSet.getString("input_type"),
                        resultSet.getString("source_interrupt_id"),
                        resultSet.getString("input_content"),
                        resultSet.getString("output_type"),
                        resultSet.getString("output_content")
                )
        );
    }

    private List<ChatMessage> expectedAskTaskHistory() {
        return List.of(
                ChatMessage.user(ORIGINAL_QUESTION),
                ChatMessage.assistant(CLARIFICATION),
                ChatMessage.user(INTERRUPT_REPLY),
                ChatMessage.assistant(RESUMED_ANSWER)
        );
    }

    private List<ChatMessage> expectedCompleteHistory() {
        return List.of(
                ChatMessage.user(ORIGINAL_QUESTION),
                ChatMessage.assistant(CLARIFICATION),
                ChatMessage.user(INTERRUPT_REPLY),
                ChatMessage.assistant(RESUMED_ANSWER),
                ChatMessage.user(FOLLOW_UP_QUESTION),
                ChatMessage.assistant(FOLLOW_UP_ANSWER)
        );
    }

    private List<PersistedTurn> expectedTurns(String interruptId) {
        return List.of(
                new PersistedTurn(
                        1,
                        ASK_TASK_ID,
                        0,
                        "ORIGINAL_QUESTION",
                        null,
                        ORIGINAL_QUESTION,
                        "ASK_CLARIFICATION",
                        CLARIFICATION
                ),
                new PersistedTurn(
                        2,
                        ASK_TASK_ID,
                        1,
                        "INTERRUPT_REPLY",
                        interruptId,
                        INTERRUPT_REPLY,
                        "FINAL_ANSWER",
                        RESUMED_ANSWER
                ),
                new PersistedTurn(
                        3,
                        FOLLOW_UP_TASK_ID,
                        0,
                        "ORIGINAL_QUESTION",
                        null,
                        FOLLOW_UP_QUESTION,
                        "FINAL_ANSWER",
                        FOLLOW_UP_ANSWER
                )
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestartE2EConfiguration {

        @Bean
        @Primary
        Clock restartE2EClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ScriptedLlmService restartE2ELlmService(
                Environment environment
        ) {
            return new ScriptedLlmService(
                    AgentActionType.valueOf(
                            environment.getRequiredProperty(
                                    "test.e2e.action"
                            )
                    ),
                    environment.getRequiredProperty(
                            "test.e2e.final-answer"
                    )
            );
        }
    }

    static final class ScriptedLlmService implements LLMService {

        private final AgentActionType actionType;
        private final String finalAnswer;
        private final List<List<ChatMessage>> plannerRequests =
                new ArrayList<>();
        private final List<List<ChatMessage>> finalAnswerRequests =
                new ArrayList<>();

        private ScriptedLlmService(
                AgentActionType actionType,
                String finalAnswer
        ) {
            this.actionType = Objects.requireNonNull(actionType);
            this.finalAnswer = Objects.requireNonNull(finalAnswer);
        }

        @Override
        public String chat(ChatRequest request) {
            List<ChatMessage> messages = detach(
                    Objects.requireNonNull(
                            request.getMessages(),
                            "messages cannot be null"
                    )
            );
            if (messages.isEmpty()) {
                throw new IllegalStateException("messages cannot be empty");
            }
            String prompt = messages.get(messages.size() - 1).getContent();
            if (prompt.contains("你是 Agent Planner")) {
                plannerRequests.add(messages);
                return plannerAction();
            }
            if (prompt.contains("你是 Agent 最终回答生成器")) {
                finalAnswerRequests.add(messages);
                return finalAnswer;
            }
            throw new IllegalStateException("unexpected LLM request");
        }

        private String plannerAction() {
            return switch (actionType) {
                case ASK_CLARIFICATION -> """
                        {
                          "type": "ASK_CLARIFICATION",
                          "thought": "repository is required",
                          "arguments": {
                            "question": "Which repository?"
                          }
                        }
                        """;
                case FINAL_ANSWER -> """
                        {
                          "type": "FINAL_ANSWER",
                          "thought": "enough information is available",
                          "arguments": {}
                        }
                        """;
                default -> throw new IllegalStateException(
                        "unsupported scripted action " + actionType
                );
            };
        }

        private List<ChatMessage> detach(List<ChatMessage> messages) {
            return messages.stream()
                    .map(message -> new ChatMessage(
                            message.getRole(),
                            message.getContent()
                    ))
                    .toList();
        }

        int plannerCallCount() {
            return plannerRequests.size();
        }

        int finalAnswerCallCount() {
            return finalAnswerRequests.size();
        }

        int totalCallCount() {
            return plannerCallCount() + finalAnswerCallCount();
        }

        List<ChatMessage> plannerMessages(int index) {
            return plannerRequests.get(index);
        }
    }

    private record PersistedTurn(
            long sequence,
            String taskId,
            int terminalStepIndex,
            String inputType,
            String sourceInterruptId,
            String inputContent,
            String outputType,
            String outputContent
    ) {
    }
}
