package com.koawa.agent.agent.checkpoint.snapshot;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionPermit;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL checkpoint writer that applies revision CAS and lease fencing in
 * the same update statement.
 */
public final class JdbcAgentFencedCheckpointWriter
        implements AgentFencedCheckpointWriter {

    private static final String FENCED_UPDATE_SQL = """
            UPDATE agent_checkpoint AS current_checkpoint
            SET conversation_id = ?,
                user_id = ?,
                revision = ?,
                status = ?,
                schema_version = ?,
                snapshot_json = ?,
                created_at = ?,
                updated_at = ?
            WHERE current_checkpoint.task_id = ?
              AND current_checkpoint.revision = ?
              AND EXISTS (
                  SELECT 1
                  FROM agent_execution_lease AS lease
                  WHERE lease.task_id = current_checkpoint.task_id
                    AND lease.owner_id = ?
                    AND lease.fencing_token = ?
                    AND lease.lease_expires_at
                            > statement_timestamp()
                  FOR UPDATE
              )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AgentTaskSnapshotJsonCodec codec;
    private final AgentCheckpointStore checkpointStore;

    public JdbcAgentFencedCheckpointWriter(
            JdbcTemplate jdbcTemplate,
            AgentTaskSnapshotJsonCodec codec
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null"
        );
        this.codec = Objects.requireNonNull(
                codec,
                "codec cannot be null"
        );
        this.checkpointStore = new JdbcAgentCheckpointStore(
                jdbcTemplate,
                codec
        );
    }

    @Override
    public AgentTaskSnapshot save(
            AgentTaskSnapshot snapshot,
            long expectedRevision,
            AgentExecutionPermit permit
    ) {
        AgentTaskSnapshot next = Objects.requireNonNull(
                snapshot,
                "snapshot cannot be null"
        );
        AgentExecutionPermit presented = requireMatchingTask(
                next,
                permit
        );
        AgentTaskSnapshot current = checkpointStore
                .load(next.taskId())
                .orElse(null);
        try {
            CheckpointWriteValidator.validate(
                    next,
                    expectedRevision,
                    current
            );
        } catch (CheckpointConflictException conflict) {
            requireActive(presented);
            throw conflict;
        }

        int updated = jdbcTemplate.update(
                FENCED_UPDATE_SQL,
                next.conversationId(),
                next.userId(),
                next.revision(),
                next.status().name(),
                next.schemaVersion(),
                codec.encode(next),
                databaseTime(next.createdAt()),
                databaseTime(next.updatedAt()),
                next.taskId(),
                expectedRevision,
                presented.ownerId(),
                presented.fencingToken()
        );
        if (updated == 1) {
            return next;
        }

        requireActive(presented);
        Long actualRevision = checkpointStore.load(next.taskId())
                .map(AgentTaskSnapshot::revision)
                .orElse(null);
        throw new CheckpointConflictException(
                next.taskId(),
                expectedRevision,
                actualRevision
        );
    }

    private AgentExecutionPermit requireMatchingTask(
            AgentTaskSnapshot snapshot,
            AgentExecutionPermit permit
    ) {
        AgentExecutionPermit presented = Objects.requireNonNull(
                permit,
                "permit cannot be null"
        );
        if (!snapshot.taskId().equals(presented.taskId())) {
            throw new IllegalArgumentException(
                    "permit taskId must match checkpoint taskId"
            );
        }
        return presented;
    }

    private void requireActive(AgentExecutionPermit presented) {
        List<LeaseState> leases = jdbcTemplate.query(
                """
                SELECT owner_id,
                       fencing_token,
                       lease_expires_at > statement_timestamp()
                               AS active
                FROM agent_execution_lease
                WHERE task_id = ?
                """,
                (resultSet, rowNumber) -> new LeaseState(
                        resultSet.getString("owner_id"),
                        resultSet.getLong("fencing_token"),
                        resultSet.getBoolean("active")
                ),
                presented.taskId()
        );
        if (leases.isEmpty()) {
            throw leaseLost(presented, Reason.LEASE_MISSING);
        }

        LeaseState current = leases.get(0);
        if (!current.ownerId().equals(presented.ownerId())
                || current.fencingToken()
                != presented.fencingToken()) {
            throw leaseLost(
                    presented,
                    Reason.OWNER_OR_TOKEN_MISMATCH
            );
        }
        if (!current.active()) {
            throw leaseLost(presented, Reason.LEASE_EXPIRED);
        }
    }

    private AgentExecutionLeaseLostException leaseLost(
            AgentExecutionPermit permit,
            Reason reason
    ) {
        return new AgentExecutionLeaseLostException(
                permit.taskId(),
                permit.fencingToken(),
                reason
        );
    }

    private OffsetDateTime databaseTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS)
                .atOffset(ZoneOffset.UTC);
    }

    private record LeaseState(
            String ownerId,
            long fencingToken,
            boolean active
    ) {
    }
}
