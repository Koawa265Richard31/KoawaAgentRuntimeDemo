package com.koawa.agent.agent.conversation;

import com.koawa.agent.agent.domain.AgentConversationTurn;
import com.koawa.agent.agent.domain.AgentConversationTurnInput;
import com.koawa.agent.agent.exception.AgentConversationTurnConflictException;
import com.koawa.agent.framework.convention.ChatMessage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL evidence for Flyway migration, idempotency, transaction joining,
 * nullable scope identity, ordering, and concurrent sequence allocation.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresJdbcAgentConversationStoreTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private JdbcAgentConversationStore store;

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
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();

        store = new JdbcAgentConversationStore(
                jdbcTemplate,
                transactionManager
        );
    }

    @Test
    void shouldMigrateFromV2AndEnforceNullableScopeIdentity() {
        assertEquals(
                List.of("1", "2", "3"),
                jdbcTemplate.queryForList(
                        """
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success
                        ORDER BY installed_rank
                        """,
                        String.class
                )
        );

        insertHead("conversation-1", null);
        assertThrows(
                DuplicateKeyException.class,
                () -> insertHead("conversation-1", null)
        );
        insertHead("conversation-1", "anonymous");

        assertEquals(2L, count("agent_conversation_head"));
    }

    @Test
    void shouldRejectWhitespaceOnlyValuesAtTheDatabaseBoundary() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertHead("\t\n", null)
        );
        insertHead("conversation-1", "user-1");
        Long scopeId = jdbcTemplate.queryForObject(
                """
                SELECT conversation_scope_id
                FROM agent_conversation_head
                WHERE conversation_id = 'conversation-1'
                """,
                Long.class
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO agent_conversation_turn (
                            conversation_scope_id,
                            turn_sequence,
                            task_id,
                            terminal_step_index,
                            input_type,
                            source_interrupt_id,
                            input_content,
                            output_type,
                            output_content,
                            committed_at
                        ) VALUES (?, 1, 'task-1', 0,
                                  'ORIGINAL_QUESTION', NULL, ?,
                                  'FINAL_ANSWER', 'answer',
                                  statement_timestamp())
                        """,
                        scopeId,
                        "\t\n"
                )
        );
    }

    @Test
    void shouldReplayIdenticalTurnWithoutAdvancingSequence() {
        AgentConversationTurn turn = turn(
                "conversation-1",
                "user-1",
                "task-1",
                0,
                "question",
                "answer"
        );

        store.appendTurn(turn);
        store.appendTurn(turn);

        assertEquals(1L, count("agent_conversation_turn"));
        assertEquals(1L, nextSequence("conversation-1", "user-1"));
        assertEquals(
                List.of(
                        ChatMessage.user("question"),
                        ChatMessage.assistant("answer")
                ),
                store.load("conversation-1", "user-1")
        );
    }

    @Test
    void shouldRollbackAConflictingIdentityWithoutLeavingAHead() {
        store.appendTurn(turn(
                "conversation-1",
                "user-1",
                "task-1",
                0,
                "question",
                "answer"
        ));

        assertThrows(
                AgentConversationTurnConflictException.class,
                () -> store.appendTurn(turn(
                        "conversation-2",
                        "user-2",
                        "task-1",
                        0,
                        "different question",
                        "different answer"
                ))
        );

        assertEquals(1L, count("agent_conversation_head"));
        assertEquals(1L, count("agent_conversation_turn"));
        assertEquals(1L, nextSequence("conversation-1", "user-1"));
    }

    @Test
    void shouldJoinAndRollbackWithTheCallingTransaction() {
        TransactionTemplate outer = new TransactionTemplate(
                transactionManager
        );

        assertThrows(
                IllegalStateException.class,
                () -> outer.executeWithoutResult(status -> {
                    store.appendTurn(turn(
                            "conversation-1",
                            "user-1",
                            "task-1",
                            0,
                            "question",
                            "answer"
                    ));
                    throw new IllegalStateException("force rollback");
                })
        );

        assertEquals(0L, count("agent_conversation_head"));
        assertEquals(0L, count("agent_conversation_turn"));
    }

    @Test
    void shouldKeepAllTurnsButLoadOnlyLatestTenOldestFirst() {
        for (int index = 1; index <= 12; index++) {
            store.appendTurn(turn(
                    "conversation-1",
                    null,
                    "task-" + index,
                    0,
                    "question-" + index,
                    "answer-" + index
            ));
        }
        store.appendTurn(turn(
                "conversation-1",
                "anonymous",
                "task-anonymous",
                0,
                "named question",
                "named answer"
        ));

        List<ChatMessage> anonymous = store.load(
                "conversation-1",
                " "
        );

        assertEquals(12L, turnCount("conversation-1", null));
        assertEquals(20, anonymous.size());
        assertEquals(ChatMessage.user("question-3"), anonymous.get(0));
        assertEquals(
                ChatMessage.assistant("answer-12"),
                anonymous.get(19)
        );
        assertEquals(
                List.of(
                        ChatMessage.user("named question"),
                        ChatMessage.assistant("named answer")
                ),
                store.load("conversation-1", "anonymous")
        );
    }

    @Test
    void shouldPreserveAskResumeFinalTurnsForOneTask() {
        store.appendTurn(new AgentConversationTurn(
                "conversation-1",
                "user-1",
                "task-1",
                0,
                AgentConversationTurnInput.originalQuestion("question"),
                AgentConversationTurn.Outcome.ASK_CLARIFICATION,
                "clarify one"
        ));
        store.appendTurn(new AgentConversationTurn(
                "conversation-1",
                "user-1",
                "task-1",
                1,
                AgentConversationTurnInput.interruptReply(
                        "reply one",
                        "interrupt-1"
                ),
                AgentConversationTurn.Outcome.ASK_CLARIFICATION,
                "clarify two"
        ));
        store.appendTurn(new AgentConversationTurn(
                "conversation-1",
                "user-1",
                "task-1",
                2,
                AgentConversationTurnInput.interruptReply(
                        "reply two",
                        "interrupt-2"
                ),
                AgentConversationTurn.Outcome.FINAL_ANSWER,
                "final answer"
        ));

        assertEquals(
                List.of(
                        ChatMessage.user("question"),
                        ChatMessage.assistant("clarify one"),
                        ChatMessage.user("reply one"),
                        ChatMessage.assistant("clarify two"),
                        ChatMessage.user("reply two"),
                        ChatMessage.assistant("final answer")
                ),
                store.load("conversation-1", "user-1")
        );
    }

    @Test
    void shouldRetainTurnsWhenCheckpointIsDeletedAndCascadeWithHead() {
        jdbcTemplate.update(
                """
                INSERT INTO agent_checkpoint (
                    task_id,
                    conversation_id,
                    user_id,
                    revision,
                    status,
                    schema_version,
                    snapshot_json,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, 0, 'COMPLETED', 1, '{}',
                          statement_timestamp(), statement_timestamp())
                """,
                "task-1",
                "conversation-1",
                "user-1"
        );
        store.appendTurn(turn(
                "conversation-1",
                "user-1",
                "task-1",
                0,
                "question",
                "answer"
        ));

        jdbcTemplate.update(
                "DELETE FROM agent_checkpoint WHERE task_id = ?",
                "task-1"
        );

        assertEquals(1L, count("agent_conversation_turn"));
        jdbcTemplate.update("DELETE FROM agent_conversation_head");
        assertEquals(0L, count("agent_conversation_turn"));
    }

    @Test
    void shouldAllocateGaplessSequenceForConcurrentTurns() throws Exception {
        int writers = 50;
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>(writers);
        try {
            for (int index = 1; index <= writers; index++) {
                int turnNumber = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    store.appendTurn(turn(
                            "conversation-1",
                            "user-1",
                            "task-" + turnNumber,
                            0,
                            "question-" + turnNumber,
                            "answer-" + turnNumber
                    ));
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(
                java.util.stream.LongStream.rangeClosed(1, writers)
                        .boxed()
                        .toList(),
                jdbcTemplate.queryForList(
                        """
                        SELECT turn_sequence
                        FROM agent_conversation_turn
                        ORDER BY turn_sequence
                        """,
                        Long.class
                )
        );
    }

    @Test
    void shouldCoalesceConcurrentIdenticalReplay() throws Exception {
        AgentConversationTurn turn = turn(
                "conversation-1",
                "user-1",
                "task-1",
                0,
                "question",
                "answer"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> {
                await(start);
                store.appendTurn(turn);
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                store.appendTurn(turn);
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, count("agent_conversation_head"));
        assertEquals(1L, count("agent_conversation_turn"));
        assertEquals(1L, nextSequence("conversation-1", "user-1"));
    }

    @Test
    void shouldResolveConcurrentCrossScopeIdentityAsOneConflict()
            throws Exception {
        AgentConversationTurn firstTurn = turn(
                "conversation-1",
                "user-1",
                "task-shared",
                0,
                "question one",
                "answer one"
        );
        AgentConversationTurn secondTurn = turn(
                "conversation-2",
                "user-2",
                "task-shared",
                0,
                "question two",
                "answer two"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(
                    () -> appendAfterStart(firstTurn, start)
            );
            Future<Boolean> second = executor.submit(
                    () -> appendAfterStart(secondTurn, start)
            );
            start.countDown();

            long successes = List.of(
                            first.get(10, TimeUnit.SECONDS),
                            second.get(10, TimeUnit.SECONDS)
                    ).stream()
                    .filter(Boolean::booleanValue)
                    .count();

            assertEquals(1L, successes);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, count("agent_conversation_head"));
        assertEquals(1L, count("agent_conversation_turn"));
        assertEquals(
                List.of(1L),
                jdbcTemplate.queryForList(
                        """
                        SELECT next_turn_sequence
                        FROM agent_conversation_head
                        """,
                        Long.class
                )
        );
    }

    private AgentConversationTurn turn(
            String conversationId,
            String userId,
            String taskId,
            int terminalStepIndex,
            String inputContent,
            String outputContent
    ) {
        return new AgentConversationTurn(
                conversationId,
                userId,
                taskId,
                terminalStepIndex,
                AgentConversationTurnInput.originalQuestion(inputContent),
                AgentConversationTurn.Outcome.FINAL_ANSWER,
                outputContent
        );
    }

    private boolean appendAfterStart(
            AgentConversationTurn turn,
            CountDownLatch start
    ) {
        await(start);
        try {
            store.appendTurn(turn);
            return true;
        } catch (AgentConversationTurnConflictException ignored) {
            return false;
        }
    }

    private void insertHead(String conversationId, String userId) {
        jdbcTemplate.update(
                """
                INSERT INTO agent_conversation_head (
                    conversation_id,
                    user_id,
                    next_turn_sequence,
                    created_at,
                    updated_at
                ) VALUES (?, ?, 0, statement_timestamp(), statement_timestamp())
                """,
                conversationId,
                userId
        );
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table,
                Long.class
        );
        return count == null ? 0 : count;
    }

    private long nextSequence(String conversationId, String userId) {
        Long sequence = jdbcTemplate.queryForObject(
                """
                SELECT next_turn_sequence
                FROM agent_conversation_head
                WHERE conversation_id = ?
                  AND user_id IS NOT DISTINCT FROM ?
                """,
                Long.class,
                conversationId,
                userId
        );
        return sequence == null ? 0 : sequence;
    }

    private long turnCount(String conversationId, String userId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM agent_conversation_turn t
                JOIN agent_conversation_head h
                  ON h.conversation_scope_id = t.conversation_scope_id
                WHERE h.conversation_id = ?
                  AND h.user_id IS NOT DISTINCT FROM ?
                """,
                Long.class,
                conversationId,
                userId
        );
        return count == null ? 0 : count;
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
    }
}
