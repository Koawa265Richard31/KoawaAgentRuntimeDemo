package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
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

/**
 * Verifies the M0-S2 terminal repair revision against real PostgreSQL.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresAgentSnapshotRecoveryServiceTest {

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
    void shouldRepairTerminalStepWithOnePostgresRevision() {
        AgentTaskSnapshot running = runningTerminalSnapshot();
        store.save(running, AgentCheckpointStore.NO_REVISION);
        AgentSnapshotRecoveryService service =
                new AgentSnapshotRecoveryService(
                        store,
                        new AgentTaskSnapshotMapper(),
                        Clock.fixed(
                                CREATED_AT.plusSeconds(60),
                                ZoneOffset.UTC
                        ),
                        () -> "unused-interrupt"
                );

        AgentSnapshotRecoveryResult recovered =
                service.restore("postgres-terminal-task", 0);

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
                recovered.snapshot(),
                store.load("postgres-terminal-task").orElseThrow()
        );
    }

    private AgentTaskSnapshot runningTerminalSnapshot() {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                "postgres-terminal-task",
                "conversation-1",
                "user-1",
                0,
                AgentTaskStatus.RUNNING,
                "resume task",
                1,
                4,
                CREATED_AT.plusSeconds(300),
                List.of(new StepSnapshot(
                        0,
                        AgentActionType.FINAL_ANSWER,
                        "finish",
                        "{}",
                        "done",
                        "{}",
                        true,
                        null
                )),
                List.of(),
                Map.of(),
                null,
                CREATED_AT,
                CREATED_AT
        );
    }
}
