package com.koawa.agent.agent.checkpoint.lease;

import com.koawa.agent.agent.exception.AgentExecutionConflictException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * PostgreSQL lease store using database time and atomic conditional writes.
 */
public final class JdbcAgentExecutionLeaseStore
        implements AgentExecutionLeaseStore {

    private static final String PERMIT_COLUMNS = """
            task_id,
            owner_id,
            fencing_token,
            lease_expires_at
            """;

    private static final String ACQUIRE_SQL = """
            INSERT INTO agent_execution_lease AS current_lease (
                task_id,
                owner_id,
                fencing_token,
                lease_expires_at,
                updated_at
            )
            SELECT task_id,
                   ?,
                   1,
                   statement_timestamp()
                       + (? * INTERVAL '1 millisecond'),
                   statement_timestamp()
            FROM agent_checkpoint
            WHERE task_id = ?
              AND revision = ?
            ON CONFLICT (task_id) DO UPDATE
            SET owner_id = EXCLUDED.owner_id,
                fencing_token =
                    current_lease.fencing_token + 1,
                lease_expires_at = EXCLUDED.lease_expires_at,
                updated_at = EXCLUDED.updated_at
            WHERE current_lease.lease_expires_at
                    <= statement_timestamp()
            RETURNING
            """ + PERMIT_COLUMNS;

    private static final String RENEW_SQL = """
            UPDATE agent_execution_lease
            SET lease_expires_at = statement_timestamp()
                    + (? * INTERVAL '1 millisecond'),
                updated_at = statement_timestamp()
            WHERE task_id = ?
              AND owner_id = ?
              AND fencing_token = ?
              AND lease_expires_at > statement_timestamp()
            RETURNING
            """ + PERMIT_COLUMNS;

    private static final String RELEASE_SQL = """
            UPDATE agent_execution_lease
            SET lease_expires_at = statement_timestamp(),
                updated_at = statement_timestamp()
            WHERE task_id = ?
              AND owner_id = ?
              AND fencing_token = ?
              AND lease_expires_at > statement_timestamp()
            RETURNING
            """ + PERMIT_COLUMNS;

    private final JdbcTemplate jdbcTemplate;
    private final Supplier<String> ownerIdSupplier;
    private final RowMapper<AgentExecutionPermit> rowMapper =
            this::mapPermit;

    public JdbcAgentExecutionLeaseStore(JdbcTemplate jdbcTemplate) {
        this(
                jdbcTemplate,
                () -> UUID.randomUUID().toString()
        );
    }

    public JdbcAgentExecutionLeaseStore(
            JdbcTemplate jdbcTemplate,
            Supplier<String> ownerIdSupplier
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null"
        );
        this.ownerIdSupplier = Objects.requireNonNull(
                ownerIdSupplier,
                "ownerIdSupplier cannot be null"
        );
    }

    @Override
    public AgentExecutionPermit acquire(
            String taskId,
            long expectedRevision,
            Duration leaseDuration
    ) {
        String actualTaskId = requireText(taskId, "taskId");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "expectedRevision cannot be negative"
            );
        }
        long durationMillis = requireDurationMillis(leaseDuration);
        String ownerId = requireText(
                ownerIdSupplier.get(),
                "ownerId"
        );

        List<AgentExecutionPermit> acquired = jdbcTemplate.query(
                ACQUIRE_SQL,
                rowMapper,
                ownerId,
                durationMillis,
                actualTaskId,
                expectedRevision
        );
        if (!acquired.isEmpty()) {
            return acquired.get(0);
        }
        throw acquireFailure(actualTaskId, expectedRevision);
    }

    @Override
    public AgentExecutionPermit renew(
            AgentExecutionPermit permit,
            Duration leaseDuration
    ) {
        AgentExecutionPermit presented = Objects.requireNonNull(
                permit,
                "permit cannot be null"
        );
        long durationMillis = requireDurationMillis(leaseDuration);
        List<AgentExecutionPermit> renewed = jdbcTemplate.query(
                RENEW_SQL,
                rowMapper,
                durationMillis,
                presented.taskId(),
                presented.ownerId(),
                presented.fencingToken()
        );
        if (!renewed.isEmpty()) {
            return renewed.get(0);
        }
        throw leaseLost(presented);
    }

    @Override
    public void release(AgentExecutionPermit permit) {
        AgentExecutionPermit presented = Objects.requireNonNull(
                permit,
                "permit cannot be null"
        );
        List<AgentExecutionPermit> released = jdbcTemplate.query(
                RELEASE_SQL,
                rowMapper,
                presented.taskId(),
                presented.ownerId(),
                presented.fencingToken()
        );
        if (released.isEmpty()) {
            throw leaseLost(presented);
        }
    }

    @Override
    public Optional<AgentExecutionPermit> load(String taskId) {
        String actualTaskId = requireText(taskId, "taskId");
        return jdbcTemplate.query(
                        "SELECT " + PERMIT_COLUMNS
                                + """
                                 FROM agent_execution_lease
                                 WHERE task_id = ?
                                """,
                        rowMapper,
                        actualTaskId
                )
                .stream()
                .findFirst();
    }

    private RuntimeException acquireFailure(
            String taskId,
            long expectedRevision
    ) {
        Long actualRevision = loadRevision(taskId);
        if (actualRevision == null) {
            return new CheckpointNotFoundException(taskId);
        }
        if (actualRevision != expectedRevision) {
            return new CheckpointConflictException(
                    taskId,
                    expectedRevision,
                    actualRevision
            );
        }
        AgentExecutionPermit current = load(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "lease acquire returned no row for task "
                                + taskId
                ));
        return new AgentExecutionConflictException(
                taskId,
                current.expiresAt()
        );
    }

    private AgentExecutionLeaseLostException leaseLost(
            AgentExecutionPermit presented
    ) {
        Optional<AgentExecutionPermit> current =
                load(presented.taskId());
        Reason reason;
        if (current.isEmpty()) {
            reason = Reason.LEASE_MISSING;
        } else if (!current.get().ownerId().equals(
                presented.ownerId())
                || current.get().fencingToken()
                != presented.fencingToken()) {
            reason = Reason.OWNER_OR_TOKEN_MISMATCH;
        } else {
            reason = Reason.LEASE_EXPIRED;
        }
        return new AgentExecutionLeaseLostException(
                presented.taskId(),
                presented.fencingToken(),
                reason
        );
    }

    private Long loadRevision(String taskId) {
        List<Long> revisions = jdbcTemplate.query(
                """
                SELECT revision
                FROM agent_checkpoint
                WHERE task_id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                taskId
        );
        return revisions.isEmpty() ? null : revisions.get(0);
    }

    private AgentExecutionPermit mapPermit(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new AgentExecutionPermit(
                resultSet.getString("task_id"),
                resultSet.getString("owner_id"),
                resultSet.getLong("fencing_token"),
                resultSet.getObject(
                        "lease_expires_at",
                        OffsetDateTime.class
                ).toInstant()
        );
    }

    private long requireDurationMillis(Duration duration) {
        Objects.requireNonNull(
                duration,
                "leaseDuration cannot be null"
        );
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    "leaseDuration must be positive"
            );
        }
        long durationMillis = duration.toMillis();
        if (durationMillis < 1) {
            throw new IllegalArgumentException(
                    "leaseDuration must be at least one millisecond"
            );
        }
        return durationMillis;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
        return value.trim();
    }
}
