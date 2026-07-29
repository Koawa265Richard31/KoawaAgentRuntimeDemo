package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CorruptedCheckpointException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAgentCheckpointStoreTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-24T06:00:00.123456700Z");

    private final EmbeddedDatabase database =
            new EmbeddedDatabaseBuilder()
                    .generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2)
                    .addScript(
                            "classpath:db/migration/"
                                    + "V1__create_agent_checkpoint.sql")
                    .build();
    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
    private final JdbcAgentCheckpointStore store =
            new JdbcAgentCheckpointStore(
                    jdbcTemplate,
                    new AgentTaskSnapshotJsonCodec());

    @AfterEach
    void shutdownDatabase() {
        database.shutdown();
    }

    @Test
    void shouldInsertLoadAndUpdateSnapshotJson() {
        AgentTaskSnapshot initial = snapshot(
                "task-1",
                "conversation-1",
                0,
                AgentTaskStatus.RUNNING,
                0);

        assertEquals(
                initial,
                store.save(initial, AgentCheckpointStore.NO_REVISION));
        assertEquals(initial, store.load("task-1").orElseThrow());

        String persistedJson = jdbcTemplate.queryForObject(
                """
                SELECT snapshot_json
                FROM agent_checkpoint
                WHERE task_id = ?
                """,
                String.class,
                "task-1");
        assertTrue(persistedJson.contains("\"revision\":0"));

        AgentTaskSnapshot waiting = snapshot(
                "task-1",
                "conversation-1",
                1,
                AgentTaskStatus.WAITING_FOR_INPUT,
                1);

        assertEquals(waiting, store.save(waiting, 0));
        assertEquals(waiting, store.load("task-1").orElseThrow());
        assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                        """
                        SELECT revision
                        FROM agent_checkpoint
                        WHERE task_id = ?
                        """,
                        Long.class,
                        "task-1"));
    }

    @Test
    void shouldRejectDuplicateCreateAndStaleRevision() {
        AgentTaskSnapshot initial = snapshot(
                "task-1",
                "conversation-1",
                0,
                AgentTaskStatus.RUNNING,
                0);
        store.save(initial, AgentCheckpointStore.NO_REVISION);

        CheckpointConflictException duplicate = assertThrows(
                CheckpointConflictException.class,
                () -> store.save(
                        initial,
                        AgentCheckpointStore.NO_REVISION));
        assertEquals(0L, duplicate.getActualRevision());

        AgentTaskSnapshot waiting = snapshot(
                "task-1",
                "conversation-1",
                1,
                AgentTaskStatus.WAITING_FOR_APPROVAL,
                1);
        store.save(waiting, 0);

        CheckpointConflictException stale = assertThrows(
                CheckpointConflictException.class,
                () -> store.save(waiting, 0));
        assertEquals(1L, stale.getActualRevision());
    }

    @Test
    void shouldAllowOnlyOneConcurrentDatabaseWriter() throws Exception {
        store.save(
                snapshot(
                        "task-1",
                        "conversation-1",
                        0,
                        AgentTaskStatus.RUNNING,
                        0),
                AgentCheckpointStore.NO_REVISION);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Boolean> inputWriter = writer(
                snapshot(
                        "task-1",
                        "conversation-1",
                        1,
                        AgentTaskStatus.WAITING_FOR_INPUT,
                        1),
                ready,
                start);
        Callable<Boolean> approvalWriter = writer(
                snapshot(
                        "task-1",
                        "conversation-1",
                        1,
                        AgentTaskStatus.WAITING_FOR_APPROVAL,
                        1),
                ready,
                start);

        try {
            Future<Boolean> inputResult = executor.submit(inputWriter);
            Future<Boolean> approvalResult = executor.submit(approvalWriter);
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            long successfulWrites = List.of(
                            inputResult.get(2, TimeUnit.SECONDS),
                            approvalResult.get(2, TimeUnit.SECONDS))
                    .stream()
                    .filter(Boolean::booleanValue)
                    .count();

            assertEquals(1, successfulWrites);
            assertEquals(1, store.load("task-1").orElseThrow().revision());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldListDeleteAndDetectCorruptedIndexedColumns() {
        store.save(
                snapshot(
                        "task-1",
                        "conversation-1",
                        0,
                        AgentTaskStatus.RUNNING,
                        1),
                AgentCheckpointStore.NO_REVISION);
        store.save(
                snapshot(
                        "task-2",
                        "conversation-1",
                        0,
                        AgentTaskStatus.RUNNING,
                        2),
                AgentCheckpointStore.NO_REVISION);
        store.save(
                snapshot(
                        "task-3",
                        "conversation-2",
                        0,
                        AgentTaskStatus.RUNNING,
                        3),
                AgentCheckpointStore.NO_REVISION);

        assertEquals(
                List.of("task-2", "task-1"),
                store.list("conversation-1").stream()
                        .map(AgentTaskSnapshot::taskId)
                        .toList());

        jdbcTemplate.update(
                """
                UPDATE agent_checkpoint
                SET status = 'FAILED'
                WHERE task_id = 'task-1'
                """);
        assertThrows(
                CorruptedCheckpointException.class,
                () -> store.load("task-1"));

        store.delete("task-2");
        assertFalse(store.load("task-2").isPresent());
    }

    private Callable<Boolean> writer(
            AgentTaskSnapshot snapshot,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                store.save(snapshot, 0);
                return true;
            } catch (CheckpointConflictException ignored) {
                return false;
            }
        };
    }

    private AgentTaskSnapshot snapshot(
            String taskId,
            String conversationId,
            long revision,
            AgentTaskStatus status,
            long updatedMinute
    ) {
        PendingInterrupt pendingInterrupt = switch (status) {
            case WAITING_FOR_INPUT ->
                    interrupt(InterruptType.USER_INPUT, updatedMinute);
            case WAITING_FOR_APPROVAL ->
                    interrupt(InterruptType.APPROVAL, updatedMinute);
            default -> null;
        };

        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                taskId,
                conversationId,
                "user-1",
                revision,
                status,
                "question",
                0,
                4,
                CREATED_AT.plusSeconds(300),
                List.of(),
                List.of(),
                Map.of(),
                pendingInterrupt,
                CREATED_AT,
                CREATED_AT.plusSeconds(updatedMinute * 60));
    }

    private PendingInterrupt interrupt(
            InterruptType type,
            long createdMinute
    ) {
        return new PendingInterrupt(
                "interrupt-" + createdMinute,
                type,
                "Please continue",
                Map.of(),
                CREATED_AT.plusSeconds(createdMinute * 60));
    }
}
