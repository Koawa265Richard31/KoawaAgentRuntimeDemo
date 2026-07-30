package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.lease.JdbcAgentExecutionLeaseStore;
import com.koawa.agent.agent.checkpoint.resume.AgentClaimedExecution;
import com.koawa.agent.agent.checkpoint.resume.AgentInterruptConsumptionService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeClaimResult;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeClaimService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeCommand;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeService;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotJsonCodec;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.snapshot.JdbcAgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.JdbcAgentFencedCheckpointWriter;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentExecutionConflictException;
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
import java.time.Clock;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the composed Resume claim against real PostgreSQL connections.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresAgentResumeClaimServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-31T08:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
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
        new JdbcAgentCheckpointStore(
                jdbcTemplate,
                new AgentTaskSnapshotJsonCodec()
        ).save(runningSnapshot(), AgentCheckpointStore.NO_REVISION);
    }

    @Test
    void shouldAllowOnlyOneWorkerToEnterExecution() throws Exception {
        AgentResumeClaimService firstWorker = worker("worker-a");
        AgentResumeClaimService secondWorker = worker("worker-b");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> firstAttempt = attempt(
                firstWorker,
                ready,
                start
        );
        Callable<Object> secondAttempt = attempt(
                secondWorker,
                ready,
                start
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AgentClaimedExecution claimedExecution = null;
        try {
            Future<Object> first = executor.submit(firstAttempt);
            Future<Object> second = executor.submit(secondAttempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Object> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            List<AgentResumeClaimResult.Claimed> claimed =
                    outcomes.stream()
                            .filter(AgentResumeClaimResult.Claimed.class
                                    ::isInstance)
                            .map(AgentResumeClaimResult.Claimed.class::cast)
                            .toList();
            long conflicts = outcomes.stream()
                    .filter(AgentExecutionConflictException.class::isInstance)
                    .count();

            assertEquals(1, claimed.size());
            assertEquals(1, conflicts);
            claimedExecution = claimed.get(0).execution();
            claimedExecution.requireActive();
            assertEquals(
                    0,
                    claimedExecution.snapshot().revision()
            );
        } finally {
            if (claimedExecution != null) {
                claimedExecution.close();
            }
            executor.shutdownNow();
        }
    }

    private Callable<Object> attempt(
            AgentResumeClaimService service,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return service.claim(new AgentResumeCommand(
                        "postgres-resume-task",
                        0,
                        null
                ));
            } catch (RuntimeException exception) {
                return exception;
            }
        };
    }

    private AgentResumeClaimService worker(String ownerId) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        AgentTaskSnapshotJsonCodec codec =
                new AgentTaskSnapshotJsonCodec();
        JdbcAgentCheckpointStore checkpointStore =
                new JdbcAgentCheckpointStore(jdbcTemplate, codec);
        JdbcAgentExecutionLeaseStore leaseStore =
                new JdbcAgentExecutionLeaseStore(
                        jdbcTemplate,
                        () -> ownerId
                );
        JdbcAgentFencedCheckpointWriter fencedWriter =
                new JdbcAgentFencedCheckpointWriter(
                        jdbcTemplate,
                        codec
                );
        AgentTaskSnapshotMapper mapper =
                new AgentTaskSnapshotMapper();
        Clock clock = Clock.systemUTC();
        AgentCheckpointService checkpointService =
                new AgentCheckpointService(
                        checkpointStore,
                        mapper,
                        clock,
                        fencedWriter
                );
        return new AgentResumeClaimService(
                new AgentResumeService(checkpointStore),
                new AgentInterruptConsumptionService(
                        checkpointStore,
                        mapper,
                        clock
                ),
                new AgentSnapshotRecoveryService(
                        checkpointStore,
                        mapper,
                        clock,
                        fencedWriter
                ),
                leaseStore,
                checkpointService,
                clock,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10)
        );
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
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

    private AgentTaskSnapshot runningSnapshot() {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                "postgres-resume-task",
                "conversation-1",
                "user-1",
                0,
                AgentTaskStatus.RUNNING,
                "resume task",
                0,
                4,
                CREATED_AT.plusSeconds(300),
                List.of(),
                List.of(),
                Map.of(),
                null,
                CREATED_AT,
                CREATED_AT
        );
    }
}
