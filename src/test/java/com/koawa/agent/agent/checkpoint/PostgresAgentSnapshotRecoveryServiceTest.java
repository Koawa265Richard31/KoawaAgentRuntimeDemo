package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionPermit;
import com.koawa.agent.agent.checkpoint.lease.JdbcAgentExecutionLeaseStore;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryResult;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotJsonCodec;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.snapshot.JdbcAgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.JdbcAgentFencedCheckpointWriter;
import com.koawa.agent.agent.conversation.JdbcAgentConversationStore;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.service.AgentConversationStore;
import com.koawa.agent.framework.convention.ChatMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PostgreSQL evidence for the unified terminal checkpoint/turn transaction.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresAgentSnapshotRecoveryServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-29T08:00:00.123456Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private JdbcAgentCheckpointStore checkpointStore;
    private JdbcAgentConversationStore conversationStore;
    private AgentTaskSnapshotMapper mapper;
    private AgentCheckpointService checkpointService;
    private Clock clock;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();

        AgentTaskSnapshotJsonCodec codec =
                new AgentTaskSnapshotJsonCodec();
        checkpointStore = new JdbcAgentCheckpointStore(
                jdbcTemplate,
                codec
        );
        conversationStore = new JdbcAgentConversationStore(
                jdbcTemplate,
                transactionManager
        );
        mapper = new AgentTaskSnapshotMapper();
        clock = Clock.fixed(
                CREATED_AT.plusSeconds(60),
                ZoneOffset.UTC
        );
        checkpointService = new AgentCheckpointService(
                checkpointStore,
                mapper,
                clock,
                new JdbcAgentFencedCheckpointWriter(jdbcTemplate, codec),
                conversationStore,
                new TransactionTemplate(transactionManager)
        );
    }

    @Test
    void shouldRepairTerminalStepAndCommitTurnTogether() {
        AgentTaskSnapshot running = runningTerminalSnapshot(
                "postgres-terminal-task",
                AgentActionType.FINAL_ANSWER,
                "done"
        );
        save(running);

        AgentSnapshotRecoveryResult recovered = recovery(
                checkpointService,
                () -> "unused-interrupt"
        ).restore("postgres-terminal-task", 0);

        assertEquals(
                AgentSnapshotRecoveryResult.Outcome
                        .TERMINAL_STEP_REPAIRED,
                recovered.outcome()
        );
        assertEquals(1, recovered.snapshot().revision());
        assertEquals(
                AgentTaskStatus.COMPLETED,
                recovered.snapshot().status()
        );
        assertEquals(
                List.of(
                        ChatMessage.user("resume task"),
                        ChatMessage.assistant("done")
                ),
                conversationStore.load("conversation-1", "user-1")
        );
        assertEquals(1L, count("agent_conversation_turn"));
    }

    @Test
    void shouldCommitAskPromptAndTurnFromOneGeneratedInterrupt() {
        save(runningTerminalSnapshot(
                "postgres-ask-task",
                AgentActionType.ASK_CLARIFICATION,
                "Which repository?"
        ));
        AtomicInteger generatedIds = new AtomicInteger();

        AgentSnapshotRecoveryResult recovered = recovery(
                checkpointService,
                () -> "interrupt-" + generatedIds.incrementAndGet()
        ).restore("postgres-ask-task", 0);

        assertEquals(1, generatedIds.get());
        assertEquals(
                AgentTaskStatus.WAITING_FOR_INPUT,
                recovered.snapshot().status()
        );
        assertEquals(
                "Which repository?",
                recovered.snapshot().pendingInterrupt().prompt()
        );
        assertEquals(
                "Which repository?",
                jdbcTemplate.queryForObject(
                        "SELECT output_content "
                                + "FROM agent_conversation_turn",
                        String.class
                )
        );
    }

    @Test
    void shouldRollbackCheckpointAndTurnWhenAppendFailsAfterInsert() {
        AgentTaskSnapshot running = runningTerminalSnapshot(
                "postgres-rollback-task",
                AgentActionType.FINAL_ANSWER,
                "done"
        );
        save(running);
        AgentConversationStore failingStore =
                new FailAfterAppendConversationStore(conversationStore);
        AgentCheckpointService failingCommitter =
                new AgentCheckpointService(
                        checkpointStore,
                        mapper,
                        clock,
                        null,
                        failingStore,
                        checkpointServiceTransactions()
                );

        assertThrows(
                IllegalStateException.class,
                () -> recovery(
                        failingCommitter,
                        () -> "unused-interrupt"
                ).restore("postgres-rollback-task", 0)
        );

        AgentTaskSnapshot unchanged = checkpointStore.load(
                "postgres-rollback-task"
        ).orElseThrow();
        assertEquals(0, unchanged.revision());
        assertEquals(AgentTaskStatus.RUNNING, unchanged.status());
        assertEquals(0L, count("agent_conversation_head"));
        assertEquals(0L, count("agent_conversation_turn"));
    }

    @Test
    void shouldRejectStaleTerminalStateWithoutWritingTurn() {
        AgentTaskSnapshot original = runningTerminalSnapshot(
                "postgres-stale-task",
                AgentActionType.FINAL_ANSWER,
                "old answer"
        );
        save(original);
        AgentState staleState = mapper.toState(original);
        staleState.setStopReason(AgentStopReason.FINAL_ANSWER);
        staleState.setFinalAnswer("old answer");

        checkpointStore.save(
                runningTerminalSnapshot(
                        "postgres-stale-task",
                        AgentActionType.FINAL_ANSWER,
                        "old answer",
                        1,
                        Map.of("planningRecoveryAttempts", "1")
                ),
                0
        );

        assertThrows(
                CheckpointConflictException.class,
                () -> checkpointService.commitTerminal(
                        staleState,
                        AgentTaskStatus.COMPLETED,
                        null
                )
        );

        AgentTaskSnapshot current = checkpointStore.load(
                "postgres-stale-task"
        ).orElseThrow();
        assertEquals(1, current.revision());
        assertEquals(AgentTaskStatus.RUNNING, current.status());
        assertEquals(0L, count("agent_conversation_turn"));
    }

    @Test
    void shouldRejectSupersededPermitWithoutWritingTurn() {
        AgentTaskSnapshot running = runningTerminalSnapshot(
                "postgres-fenced-task",
                AgentActionType.FINAL_ANSWER,
                "done"
        );
        save(running);
        AgentState state = mapper.toState(running);
        state.setStopReason(AgentStopReason.FINAL_ANSWER);
        state.setFinalAnswer("done");
        AtomicInteger owners = new AtomicInteger();
        JdbcAgentExecutionLeaseStore leaseStore =
                new JdbcAgentExecutionLeaseStore(
                        jdbcTemplate,
                        () -> "owner-" + owners.incrementAndGet()
                );
        AgentExecutionPermit oldPermit = leaseStore.acquire(
                running.taskId(),
                0,
                Duration.ofSeconds(30)
        );
        leaseStore.release(oldPermit);
        leaseStore.acquire(
                running.taskId(),
                0,
                Duration.ofSeconds(30)
        );

        assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> checkpointService.commitTerminal(
                        state,
                        AgentTaskStatus.COMPLETED,
                        null,
                        0,
                        oldPermit
                )
        );

        AgentTaskSnapshot unchanged = checkpointStore.load(
                running.taskId()
        ).orElseThrow();
        assertEquals(0, unchanged.revision());
        assertEquals(AgentTaskStatus.RUNNING, unchanged.status());
        assertEquals(0L, count("agent_conversation_turn"));
    }

    private TransactionTemplate checkpointServiceTransactions() {
        return new TransactionTemplate(transactionManager);
    }

    private AgentSnapshotRecoveryService recovery(
            AgentCheckpointService committer,
            java.util.function.Supplier<String> interruptIds
    ) {
        return new AgentSnapshotRecoveryService(
                checkpointStore,
                mapper,
                clock,
                interruptIds,
                committer
        );
    }

    private void save(AgentTaskSnapshot snapshot) {
        checkpointStore.save(snapshot, AgentCheckpointStore.NO_REVISION);
    }

    private AgentTaskSnapshot runningTerminalSnapshot(
            String taskId,
            AgentActionType actionType,
            String output
    ) {
        return runningTerminalSnapshot(taskId, actionType, output, 0);
    }

    private AgentTaskSnapshot runningTerminalSnapshot(
            String taskId,
            AgentActionType actionType,
            String output,
            long revision
    ) {
        return runningTerminalSnapshot(
                taskId,
                actionType,
                output,
                revision,
                Map.of()
        );
    }

    private AgentTaskSnapshot runningTerminalSnapshot(
            String taskId,
            AgentActionType actionType,
            String output,
            long revision,
            Map<String, String> recoveryContext
    ) {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                taskId,
                "conversation-1",
                "user-1",
                revision,
                AgentTaskStatus.RUNNING,
                "resume task",
                1,
                4,
                CREATED_AT.plusSeconds(300),
                List.of(new StepSnapshot(
                        0,
                        actionType,
                        "finish",
                        "{}",
                        output,
                        "{}",
                        true,
                        null
                )),
                List.of(),
                recoveryContext,
                null,
                CREATED_AT,
                CREATED_AT
        );
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table,
                Long.class
        );
        return count == null ? 0 : count;
    }

    private static final class FailAfterAppendConversationStore
            implements AgentConversationStore {

        private final AgentConversationStore delegate;

        private FailAfterAppendConversationStore(
                AgentConversationStore delegate
        ) {
            this.delegate = delegate;
        }

        @Override
        public List<ChatMessage> load(
                String conversationId,
                String userId
        ) {
            return delegate.load(conversationId, userId);
        }

        @Override
        public void appendTurn(
                com.koawa.agent.agent.domain.AgentConversationTurn turn
        ) {
            delegate.appendTurn(turn);
            throw new IllegalStateException("injected after turn insert");
        }
    }
}
