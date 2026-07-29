package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CorruptedCheckpointException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC checkpoint store using revision-based compare-and-set updates.
 */
public final class JdbcAgentCheckpointStore
        implements AgentCheckpointStore {

    private static final String SELECT_COLUMNS = """
            task_id,
            conversation_id,
            user_id,
            revision,
            status,
            schema_version,
            snapshot_json,
            created_at,
            updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AgentTaskSnapshotJsonCodec codec;
    private final RowMapper<AgentTaskSnapshot> rowMapper = this::mapRow;

    public JdbcAgentCheckpointStore(
            JdbcTemplate jdbcTemplate,
            AgentTaskSnapshotJsonCodec codec
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null");
        this.codec = Objects.requireNonNull(codec, "codec cannot be null");
    }

    @Override
    public AgentTaskSnapshot save(
            AgentTaskSnapshot snapshot,
            long expectedRevision
    ) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (expectedRevision == NO_REVISION) {
            return insert(snapshot, expectedRevision);
        }

        AgentTaskSnapshot current = load(snapshot.taskId()).orElse(null);
        CheckpointWriteValidator.validate(
                snapshot,
                expectedRevision,
                current);

        int updated = jdbcTemplate.update(
                """
                UPDATE agent_checkpoint
                SET conversation_id = ?,
                    user_id = ?,
                    revision = ?,
                    status = ?,
                    schema_version = ?,
                    snapshot_json = ?,
                    created_at = ?,
                    updated_at = ?
                WHERE task_id = ?
                  AND revision = ?
                """,
                snapshot.conversationId(),
                snapshot.userId(),
                snapshot.revision(),
                snapshot.status().name(),
                snapshot.schemaVersion(),
                codec.encode(snapshot),
                databaseTime(snapshot.createdAt()),
                databaseTime(snapshot.updatedAt()),
                snapshot.taskId(),
                expectedRevision);
        if (updated != 1) {
            throw conflict(
                    snapshot.taskId(),
                    expectedRevision,
                    loadRevision(snapshot.taskId()));
        }
        return snapshot;
    }

    @Override
    public Optional<AgentTaskSnapshot> load(String taskId) {
        requireText(taskId, "taskId");
        return jdbcTemplate.query(
                        "SELECT " + SELECT_COLUMNS
                                + " FROM agent_checkpoint WHERE task_id = ?",
                        rowMapper,
                        taskId)
                .stream()
                .findFirst();
    }

    @Override
    public List<AgentTaskSnapshot> list(String conversationId) {
        requireText(conversationId, "conversationId");
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS
                        + """
                         FROM agent_checkpoint
                         WHERE conversation_id = ?
                         ORDER BY updated_at DESC, task_id
                        """,
                rowMapper,
                conversationId);
    }

    @Override
    public void delete(String taskId) {
        requireText(taskId, "taskId");
        jdbcTemplate.update(
                "DELETE FROM agent_checkpoint WHERE task_id = ?",
                taskId);
    }

    private AgentTaskSnapshot insert(
            AgentTaskSnapshot snapshot,
            long expectedRevision
    ) {
        CheckpointWriteValidator.validate(
                snapshot,
                expectedRevision,
                null);
        try {
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
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    snapshot.taskId(),
                    snapshot.conversationId(),
                    snapshot.userId(),
                    snapshot.revision(),
                    snapshot.status().name(),
                    snapshot.schemaVersion(),
                    codec.encode(snapshot),
                    databaseTime(snapshot.createdAt()),
                    databaseTime(snapshot.updatedAt()));
            return snapshot;
        } catch (DuplicateKeyException exception) {
            throw conflict(
                    snapshot.taskId(),
                    expectedRevision,
                    loadRevision(snapshot.taskId()));
        }
    }

    private AgentTaskSnapshot mapRow(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        String taskId = resultSet.getString("task_id");
        AgentTaskSnapshot snapshot = codec.decode(
                resultSet.getString("snapshot_json"));

        requireEqual(taskId, "taskId", taskId, snapshot.taskId());
        requireEqual(
                taskId,
                "conversationId",
                resultSet.getString("conversation_id"),
                snapshot.conversationId());
        requireEqual(
                taskId,
                "userId",
                resultSet.getString("user_id"),
                snapshot.userId());
        requireEqual(
                taskId,
                "revision",
                resultSet.getLong("revision"),
                snapshot.revision());
        requireEqual(
                taskId,
                "status",
                resultSet.getString("status"),
                snapshot.status().name());
        requireEqual(
                taskId,
                "schemaVersion",
                resultSet.getInt("schema_version"),
                snapshot.schemaVersion());
        requireEqual(
                taskId,
                "createdAt",
                readTime(resultSet, "created_at"),
                truncateToDatabasePrecision(snapshot.createdAt()));
        requireEqual(
                taskId,
                "updatedAt",
                readTime(resultSet, "updated_at"),
                truncateToDatabasePrecision(snapshot.updatedAt()));
        return snapshot;
    }

    private Long loadRevision(String taskId) {
        List<Long> revisions = jdbcTemplate.query(
                "SELECT revision FROM agent_checkpoint WHERE task_id = ?",
                (resultSet, rowNumber) -> resultSet.getLong(1),
                taskId);
        return revisions.isEmpty() ? null : revisions.get(0);
    }

    private OffsetDateTime databaseTime(Instant instant) {
        return truncateToDatabasePrecision(instant)
                .atOffset(ZoneOffset.UTC);
    }

    private Instant readTime(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private Instant truncateToDatabasePrecision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private void requireEqual(
            String taskId,
            String field,
            Object indexedValue,
            Object snapshotValue
    ) {
        if (!Objects.equals(indexedValue, snapshotValue)) {
            throw new CorruptedCheckpointException(
                    taskId,
                    field + " column does not match Snapshot JSON");
        }
    }

    private CheckpointConflictException conflict(
            String taskId,
            long expectedRevision,
            Long actualRevision
    ) {
        return new CheckpointConflictException(
                taskId,
                expectedRevision,
                actualRevision);
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank");
        }
    }
}
