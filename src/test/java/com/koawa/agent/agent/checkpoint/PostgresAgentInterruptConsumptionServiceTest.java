package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.resume.*;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotJsonCodec;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.snapshot.JdbcAgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.CheckpointConflictException;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies USER_INPUT interrupt consumption against real PostgreSQL.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresAgentInterruptConsumptionServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-29T08:00:00.123456Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcAgentCheckpointStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS agent_checkpoint");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(
                            "db/migration/V1__create_agent_checkpoint.sql"
                    )
            );
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "failed to prepare PostgreSQL schema",
                    exception
            );
        }
        store = new JdbcAgentCheckpointStore(
                jdbcTemplate,
                new AgentTaskSnapshotJsonCodec()
        );
    }

    @Test
    void shouldConsumeOnlyOnceWithPostgresRevisionCas() {
        store.save(
                waitingSnapshot(),
                AgentCheckpointStore.NO_REVISION
        );
        AgentInterruptConsumptionService service =
                new AgentInterruptConsumptionService(
                        store,
                        new AgentTaskSnapshotMapper(),
                        Clock.fixed(
                                CREATED_AT.plusSeconds(60),
                                ZoneOffset.UTC
                        )
                );
        AgentResumeCommand command = new AgentResumeCommand(
                "postgres-input-task",
                0,
                "interrupt-1",
                "repository-a"
        );

        AgentInterruptConsumptionResult consumed =
                service.consume(command);

        assertEquals(1, consumed.snapshot().revision());
        assertEquals(
                AgentTaskStatus.RUNNING,
                consumed.snapshot().status()
        );
        assertNull(consumed.snapshot().pendingInterrupt());
        assertEquals(
                "repository-a",
                consumed.snapshot().historySnapshot()
                        .get(0)
                        .content()
        );
        assertEquals(
                consumed.snapshot(),
                store.load("postgres-input-task").orElseThrow()
        );
        AgentSnapshotRecoveryResult restarted =
                new AgentSnapshotRecoveryService(
                        store,
                        new AgentTaskSnapshotMapper()
                ).restore("postgres-input-task", 1);
        assertTrue(restarted.shouldContinue());
        assertThrows(
                CheckpointConflictException.class,
                () -> service.consume(command)
        );
    }

    private AgentTaskSnapshot waitingSnapshot() {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                "postgres-input-task",
                "conversation-1",
                "user-1",
                0,
                AgentTaskStatus.WAITING_FOR_INPUT,
                "resume task",
                1,
                4,
                CREATED_AT.plusSeconds(300),
                List.of(new StepSnapshot(
                        0,
                        AgentActionType.ASK_CLARIFICATION,
                        "need repository",
                        "{}",
                        "Which repository?",
                        "{}",
                        true,
                        null
                )),
                List.of(),
                Map.of(
                        "stopReason",
                        AgentStopReason.ASK_CLARIFICATION.name(),
                        "finalAnswer",
                        "Which repository?"
                ),
                new PendingInterrupt(
                        "interrupt-1",
                        InterruptType.USER_INPUT,
                        "Which repository?",
                        Map.of(),
                        CREATED_AT
                ),
                CREATED_AT,
                CREATED_AT
        );
    }
}
