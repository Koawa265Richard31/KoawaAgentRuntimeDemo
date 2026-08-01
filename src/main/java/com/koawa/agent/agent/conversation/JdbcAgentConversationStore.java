package com.koawa.agent.agent.conversation;

import com.koawa.agent.agent.domain.AgentConversationTurn;
import com.koawa.agent.agent.domain.AgentConversationTurnInput;
import com.koawa.agent.agent.exception.AgentConversationTurnConflictException;
import com.koawa.agent.agent.service.AgentConversationStore;
import com.koawa.agent.framework.convention.ChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL-backed cross-task conversation projection.
 *
 * <p>Each append joins an existing transaction or creates a short local one.
 * A per-conversation head row serializes sequence allocation.</p>
 */
public final class JdbcAgentConversationStore
        implements AgentConversationStore {

    private static final int MAX_TURNS = 10;

    private static final String CREATE_HEAD_SQL = """
            INSERT INTO agent_conversation_head (
                conversation_id,
                user_id,
                next_turn_sequence,
                created_at,
                updated_at
            ) VALUES (?, ?, 0, statement_timestamp(), statement_timestamp())
            ON CONFLICT ON CONSTRAINT uq_agent_conversation_head_scope
            DO NOTHING
            """;

    private static final String LOCK_HEAD_SQL = """
            SELECT conversation_scope_id
            FROM agent_conversation_head
            WHERE conversation_id = ?
              AND user_id IS NOT DISTINCT FROM ?
            FOR UPDATE
            """;

    private static final String LOAD_IDENTITY_SQL = """
            SELECT h.conversation_id,
                   h.user_id,
                   t.task_id,
                   t.terminal_step_index,
                   t.input_type,
                   t.source_interrupt_id,
                   t.input_content,
                   t.output_type,
                   t.output_content
            FROM agent_conversation_turn t
            JOIN agent_conversation_head h
              ON h.conversation_scope_id = t.conversation_scope_id
            WHERE t.task_id = ?
              AND t.terminal_step_index = ?
            """;

    private static final String NEXT_SEQUENCE_SQL = """
            UPDATE agent_conversation_head
            SET next_turn_sequence = next_turn_sequence + 1,
                updated_at = statement_timestamp()
            WHERE conversation_scope_id = ?
            RETURNING next_turn_sequence
            """;

    private static final String INSERT_TURN_SQL = """
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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp())
            ON CONFLICT ON CONSTRAINT uq_agent_conversation_turn_identity
            DO NOTHING
            """;

    private static final String LOAD_RECENT_SQL = """
            SELECT input_content, output_content
            FROM (
                SELECT t.turn_sequence,
                       t.input_content,
                       t.output_content
                FROM agent_conversation_turn t
                JOIN agent_conversation_head h
                  ON h.conversation_scope_id = t.conversation_scope_id
                WHERE h.conversation_id = ?
                  AND h.user_id IS NOT DISTINCT FROM ?
                ORDER BY t.turn_sequence DESC
                LIMIT ?
            ) recent
            ORDER BY turn_sequence ASC
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcAgentConversationStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null"
        );
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(
                        transactionManager,
                        "transactionManager cannot be null"
                )
        );
    }

    @Override
    public void appendTurn(AgentConversationTurn turn) {
        AgentConversationTurn actualTurn = Objects.requireNonNull(
                turn,
                "turn cannot be null"
        );
        transactionTemplate.executeWithoutResult(
                status -> appendInTransaction(actualTurn)
        );
    }

    @Override
    public List<ChatMessage> load(
            String conversationId,
            String userId
    ) {
        String actualConversationId = requireText(
                conversationId,
                "conversationId"
        );
        String actualUserId = normalizeUserId(userId);
        List<TurnContent> turns = jdbcTemplate.query(
                LOAD_RECENT_SQL,
                this::mapTurnContent,
                actualConversationId,
                actualUserId,
                MAX_TURNS
        );
        List<ChatMessage> messages = new ArrayList<>(turns.size() * 2);
        for (TurnContent turn : turns) {
            messages.add(ChatMessage.user(turn.inputContent()));
            messages.add(ChatMessage.assistant(turn.outputContent()));
        }
        return List.copyOf(messages);
    }

    private void appendInTransaction(AgentConversationTurn turn) {
        jdbcTemplate.update(
                CREATE_HEAD_SQL,
                turn.conversationId(),
                turn.userId()
        );
        long scopeId = lockHead(turn);
        Optional<AgentConversationTurn> existing = loadByIdentity(
                turn.taskId(),
                turn.terminalStepIndex()
        );
        if (existing.isPresent()) {
            requireMatching(existing.get(), turn);
            return;
        }

        Long sequence = jdbcTemplate.queryForObject(
                NEXT_SEQUENCE_SQL,
                Long.class,
                scopeId
        );
        int inserted = jdbcTemplate.update(
                INSERT_TURN_SQL,
                scopeId,
                Objects.requireNonNull(
                        sequence,
                        "allocated turn sequence cannot be null"
                ),
                turn.taskId(),
                turn.terminalStepIndex(),
                turn.input().type().name(),
                turn.input().sourceInterruptId(),
                turn.input().content(),
                turn.outcome().name(),
                turn.outputContent()
        );
        if (inserted == 0) {
            AgentConversationTurn concurrent = loadByIdentity(
                    turn.taskId(),
                    turn.terminalStepIndex()
            ).orElseThrow(() -> new IllegalStateException(
                    "conflicting conversation turn is not visible"
            ));
            requireMatching(concurrent, turn);
            // Equal replays in one scope are serialized by LOCK_HEAD_SQL and
            // return before sequence allocation. Reaching this branch with an
            // equal payload would violate that locking protocol, so roll back
            // instead of committing an unused sequence.
            throw new IllegalStateException(
                    "matching turn bypassed conversation head lock"
            );
        }
    }

    private long lockHead(AgentConversationTurn turn) {
        List<Long> scopeIds = jdbcTemplate.query(
                LOCK_HEAD_SQL,
                (resultSet, rowNumber) ->
                        resultSet.getLong("conversation_scope_id"),
                turn.conversationId(),
                turn.userId()
        );
        if (scopeIds.size() != 1) {
            throw new IllegalStateException(
                    "conversation head could not be locked"
            );
        }
        return scopeIds.get(0);
    }

    private Optional<AgentConversationTurn> loadByIdentity(
            String taskId,
            int terminalStepIndex
    ) {
        return jdbcTemplate.query(
                        LOAD_IDENTITY_SQL,
                        this::mapTurn,
                        taskId,
                        terminalStepIndex
                )
                .stream()
                .findFirst();
    }

    private void requireMatching(
            AgentConversationTurn existing,
            AgentConversationTurn attempted
    ) {
        if (!existing.equals(attempted)) {
            throw new AgentConversationTurnConflictException(
                    attempted.taskId(),
                    attempted.terminalStepIndex()
            );
        }
    }

    private AgentConversationTurn mapTurn(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        AgentConversationTurnInput input =
                new AgentConversationTurnInput(
                        AgentConversationTurnInput.Type.valueOf(
                                resultSet.getString("input_type")
                        ),
                        resultSet.getString("input_content"),
                        resultSet.getString("source_interrupt_id")
                );
        return new AgentConversationTurn(
                resultSet.getString("conversation_id"),
                resultSet.getString("user_id"),
                resultSet.getString("task_id"),
                resultSet.getInt("terminal_step_index"),
                input,
                AgentConversationTurn.Outcome.valueOf(
                        resultSet.getString("output_type")
                ),
                resultSet.getString("output_content")
        );
    }

    private TurnContent mapTurnContent(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new TurnContent(
                resultSet.getString("input_content"),
                resultSet.getString("output_content")
        );
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank()
                ? null
                : userId.trim();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
        return value.trim();
    }

    private record TurnContent(
            String inputContent,
            String outputContent
    ) {
    }
}
