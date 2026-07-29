package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.MessageSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentTaskSnapshotCodecException;
import com.koawa.agent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskSnapshotJsonCodecTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-24T06:00:00.123456Z");
    private static final Instant UPDATED_AT =
            Instant.parse("2026-07-24T06:01:00.654321Z");

    private final AgentTaskSnapshotJsonCodec codec =
            new AgentTaskSnapshotJsonCodec();

    @Test
    void shouldRoundTripCompleteSnapshotWithoutLosingFields() {
        AgentTaskSnapshot original = snapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION);

        String json = codec.encode(original);
        AgentTaskSnapshot decoded = codec.decode(json);

        assertEquals(original, decoded);
        assertTrue(json.contains("\"schemaVersion\":1"));
        assertTrue(json.contains("\"updatedAt\":\"2026-07-24T06:01:00.654321Z\""));
    }

    @Test
    void shouldRejectMalformedBlankAndNullJson() {
        assertThrows(
                AgentTaskSnapshotCodecException.class,
                () -> codec.decode("{not-json}"));
        assertThrows(
                AgentTaskSnapshotCodecException.class,
                () -> codec.decode(" "));
        assertThrows(
                AgentTaskSnapshotCodecException.class,
                () -> codec.decode("null"));
    }

    @Test
    void shouldRejectUnsupportedSchemaVersionWhenEncoding() {
        AgentTaskSnapshot unsupported = snapshot(2);

        AgentTaskSnapshotCodecException exception = assertThrows(
                AgentTaskSnapshotCodecException.class,
                () -> codec.encode(unsupported));

        assertTrue(exception.getMessage().contains("schemaVersion 2"));
    }

    @Test
    void shouldRejectUnsupportedSchemaVersionWhenDecoding() {
        String json = codec.encode(snapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION));
        String unsupportedJson = json.replaceFirst(
                "\"schemaVersion\":1",
                "\"schemaVersion\":2");

        AgentTaskSnapshotCodecException exception = assertThrows(
                AgentTaskSnapshotCodecException.class,
                () -> codec.decode(unsupportedJson));

        assertTrue(exception.getMessage().contains("schemaVersion 2"));
    }

    private AgentTaskSnapshot snapshot(int schemaVersion) {
        return new AgentTaskSnapshot(
                schemaVersion,
                "task-1",
                "conversation-1",
                "user-1",
                2,
                AgentTaskStatus.WAITING_FOR_APPROVAL,
                "approve order cancellation",
                1,
                8,
                Instant.parse("2026-07-24T06:05:00Z"),
                List.of(new StepSnapshot(
                        0,
                        AgentActionType.CALL_MCP_TOOL,
                        "query order",
                        "{\"orderId\":\"10086\"}",
                        "waiting for shipment",
                        "{\"latencyMs\":125}",
                        true,
                        null)),
                List.of(
                        new MessageSnapshot(
                                ChatMessage.Role.USER,
                                "query order"),
                        new MessageSnapshot(
                                ChatMessage.Role.ASSISTANT,
                                "waiting for approval")),
                Map.of(
                        "planningRecoveryAttempts",
                        "1",
                        "stopReason",
                        "ASK_CLARIFICATION"),
                new PendingInterrupt(
                        "interrupt-1",
                        InterruptType.APPROVAL,
                        "Approve cancellation?",
                        Map.of(
                                "toolName",
                                "order.cancel"),
                        UPDATED_AT),
                CREATED_AT,
                UPDATED_AT);
    }
}
