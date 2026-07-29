package com.koawa.agent.agent.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.exception.AgentTaskSnapshotCodecException;

import java.util.Objects;

/**
 * Version-aware JSON codec for persistent agent task snapshots.
 */
public final class AgentTaskSnapshotJsonCodec {

    private final ObjectMapper objectMapper;

    public AgentTaskSnapshotJsonCodec() {
        this(JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build());
    }

    public AgentTaskSnapshotJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper cannot be null");
    }

    public String encode(AgentTaskSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        requireSupportedVersion(snapshot);

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new AgentTaskSnapshotCodecException(
                    "failed to encode checkpoint for task "
                            + snapshot.taskId(),
                    exception);
        }
    }

    public AgentTaskSnapshot decode(String json) {
        if (json == null || json.isBlank()) {
            throw new AgentTaskSnapshotCodecException(
                    "checkpoint JSON cannot be blank");
        }

        try {
            AgentTaskSnapshot snapshot = objectMapper.readValue(
                    json,
                    AgentTaskSnapshot.class);
            if (snapshot == null) {
                throw new AgentTaskSnapshotCodecException(
                        "checkpoint JSON cannot be null");
            }
            requireSupportedVersion(snapshot);
            return snapshot;
        } catch (JsonProcessingException exception) {
            throw new AgentTaskSnapshotCodecException(
                    "failed to decode checkpoint JSON",
                    exception);
        }
    }

    private void requireSupportedVersion(AgentTaskSnapshot snapshot) {
        if (snapshot.schemaVersion()
                != AgentTaskSnapshot.CURRENT_SCHEMA_VERSION) {
            throw new AgentTaskSnapshotCodecException(
                    "unsupported checkpoint schemaVersion "
                            + snapshot.schemaVersion()
                            + ", expected "
                            + AgentTaskSnapshot.CURRENT_SCHEMA_VERSION);
        }
    }
}
