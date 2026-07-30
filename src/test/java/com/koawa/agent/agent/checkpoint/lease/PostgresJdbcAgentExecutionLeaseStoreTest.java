package com.koawa.agent.agent.checkpoint.lease;

import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotJsonCodec;
import com.koawa.agent.agent.checkpoint.snapshot.JdbcAgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentExecutionConflictException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies execution lease semantics against real PostgreSQL.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresJdbcAgentExecutionLeaseStoreTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-30T08:00:00Z");
    private static final Duration LEASE_DURATION =
            Duration.ofSeconds(30);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private JdbcAgentCheckpointStore checkpointStore;
    private JdbcAgentExecutionLeaseStore leaseStore;
    private AtomicInteger ownerSequence;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                "DROP TABLE IF EXISTS agent_execution_lease"
        );
        jdbcTemplate.execute("DROP TABLE IF EXISTS agent_checkpoint");
        executeMigration(
                dataSource,
                "db/migration/V1__create_agent_checkpoint.sql"
        );
        executeMigration(
                dataSource,
                "db/migration/V2__create_agent_execution_lease.sql"
        );
        checkpointStore = new JdbcAgentCheckpointStore(
                jdbcTemplate,
                new AgentTaskSnapshotJsonCodec()
        );
        ownerSequence = new AtomicInteger();
        leaseStore = new JdbcAgentExecutionLeaseStore(
                jdbcTemplate,
                () -> "owner-" + ownerSequence.incrementAndGet()
        );
    }

    @Test
    void shouldAcquireRenewReleaseAndRetainTokenHistory() {
        saveCheckpoint("task-1");

        AgentExecutionPermit first = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );
        AgentExecutionPermit renewed = leaseStore.renew(
                first,
                LEASE_DURATION
        );

        assertEquals(1, first.fencingToken());
        assertEquals(first.ownerId(), renewed.ownerId());
        assertEquals(first.fencingToken(), renewed.fencingToken());
        assertFalse(
                renewed.expiresAt().isBefore(first.expiresAt())
        );

        leaseStore.release(renewed);
        AgentExecutionPermit released = leaseStore.load("task-1")
                .orElseThrow();
        AgentExecutionPermit second = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );

        assertEquals(1, released.fencingToken());
        assertEquals(2, second.fencingToken());
        assertNotEquals(first.ownerId(), second.ownerId());
        assertEquals(
                0,
                checkpointStore.load("task-1")
                        .orElseThrow()
                        .revision()
        );
    }

    @Test
    void shouldRejectMissingStaleAndActiveAcquire() {
        assertThrows(
                CheckpointNotFoundException.class,
                () -> leaseStore.acquire(
                        "missing-task",
                        0,
                        LEASE_DURATION
                )
        );
        saveCheckpoint("task-1");
        CheckpointConflictException stale = assertThrows(
                CheckpointConflictException.class,
                () -> leaseStore.acquire(
                        "task-1",
                        1,
                        LEASE_DURATION
                )
        );
        AgentExecutionPermit first = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );
        AgentExecutionConflictException active = assertThrows(
                AgentExecutionConflictException.class,
                () -> leaseStore.acquire(
                        "task-1",
                        0,
                        LEASE_DURATION
                )
        );

        assertEquals(0L, stale.getActualRevision());
        assertEquals(first.expiresAt(), active.getRetryAt());
    }

    @Test
    void shouldTakeOverExpiredLeaseAndFenceOldPermit() {
        saveCheckpoint("task-1");
        AgentExecutionPermit first = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );
        expireLease("task-1");

        AgentExecutionLeaseLostException expired = assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> leaseStore.renew(first, LEASE_DURATION)
        );
        AgentExecutionPermit second = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );
        AgentExecutionLeaseLostException fencedRenew = assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> leaseStore.renew(first, LEASE_DURATION)
        );
        AgentExecutionLeaseLostException fencedRelease = assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> leaseStore.release(first)
        );

        assertEquals(Reason.LEASE_EXPIRED, expired.getReason());
        assertEquals(2, second.fencingToken());
        assertEquals(
                Reason.OWNER_OR_TOKEN_MISMATCH,
                fencedRenew.getReason()
        );
        assertEquals(
                Reason.OWNER_OR_TOKEN_MISMATCH,
                fencedRelease.getReason()
        );
    }

    @Test
    void shouldAllowOnlyOneConcurrentAcquire() throws Exception {
        saveCheckpoint("task-1");
        JdbcAgentExecutionLeaseStore firstStore =
                new JdbcAgentExecutionLeaseStore(
                        jdbcTemplate,
                        () -> "concurrent-owner-a"
                );
        JdbcAgentExecutionLeaseStore secondStore =
                new JdbcAgentExecutionLeaseStore(
                        jdbcTemplate,
                        () -> "concurrent-owner-b"
                );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(acquire(
                    firstStore,
                    ready,
                    start
            ));
            Future<Boolean> second = executor.submit(acquire(
                    secondStore,
                    ready,
                    start
            ));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            long acquiredCount = List.of(
                            first.get(5, TimeUnit.SECONDS),
                            second.get(5, TimeUnit.SECONDS)
                    )
                    .stream()
                    .filter(Boolean::booleanValue)
                    .count();

            assertEquals(1, acquiredCount);
            assertEquals(
                    1,
                    leaseStore.load("task-1")
                            .orElseThrow()
                            .fencingToken()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldDeleteLeaseWithCheckpoint() {
        saveCheckpoint("task-1");
        leaseStore.acquire("task-1", 0, LEASE_DURATION);

        checkpointStore.delete("task-1");

        assertTrue(leaseStore.load("task-1").isEmpty());
    }

    private Callable<Boolean> acquire(
            JdbcAgentExecutionLeaseStore store,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                store.acquire("task-1", 0, LEASE_DURATION);
                return true;
            } catch (AgentExecutionConflictException ignored) {
                return false;
            }
        };
    }

    private void expireLease(String taskId) {
        jdbcTemplate.update(
                """
                UPDATE agent_execution_lease
                SET lease_expires_at =
                        statement_timestamp() - INTERVAL '1 second',
                    updated_at = statement_timestamp()
                WHERE task_id = ?
                """,
                taskId
        );
    }

    private void saveCheckpoint(String taskId) {
        checkpointStore.save(
                new AgentTaskSnapshot(
                        AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                        taskId,
                        "conversation-1",
                        "user-1",
                        0,
                        AgentTaskStatus.RUNNING,
                        "question",
                        0,
                        4,
                        CREATED_AT.plusSeconds(300),
                        List.of(),
                        List.of(),
                        Map.of(),
                        null,
                        CREATED_AT,
                        CREATED_AT
                ),
                AgentCheckpointStore.NO_REVISION
        );
    }

    private void executeMigration(
            DriverManagerDataSource dataSource,
            String path
    ) {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(path)
            );
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "failed to prepare PostgreSQL schema",
                    exception
            );
        }
    }
}
