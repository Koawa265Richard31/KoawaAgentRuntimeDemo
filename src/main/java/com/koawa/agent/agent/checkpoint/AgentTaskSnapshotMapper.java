package com.koawa.agent.agent.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentFailureType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStep;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.MessageSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentTaskSnapshotMappingException;
import com.koawa.agent.framework.convention.ChatMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts mutable runtime state to and from immutable checkpoint data.
 */
public final class AgentTaskSnapshotMapper {

    static final String PLANNING_RECOVERY_ATTEMPTS =
            "planningRecoveryAttempts";
    static final String FINAL_ANSWER = "finalAnswer";
    static final String STOP_REASON = "stopReason";
    static final String FAILURE_TYPE = "failureType";
    static final String ERROR_MESSAGE = "errorMessage";

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    public AgentTaskSnapshotMapper() {
        this(new ObjectMapper());
    }

    public AgentTaskSnapshotMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper cannot be null");
    }

    public AgentTaskSnapshot toSnapshot(
            AgentState state,
            AgentTaskStatus status,
            long revision,
            PendingInterrupt pendingInterrupt,
            Instant createdAt,
            Instant updatedAt
    ) {
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(status, "status cannot be null");

        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                state.getTaskId(),
                state.getConversationId(),
                state.getUserId(),
                revision,
                status,
                state.getOriginalQuestion(),
                state.getCurrentStep(),
                state.getMaxSteps(),
                state.getDeadlineAt(),
                toStepSnapshots(state.getSteps()),
                toMessageSnapshots(state.getHistorySnapshot()),
                toRecoveryContext(state),
                pendingInterrupt,
                createdAt,
                updatedAt);
    }

    public AgentState toState(AgentTaskSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");

        return AgentState.builder()
                .conversationId(snapshot.conversationId())
                .taskId(snapshot.taskId())
                .userId(snapshot.userId())
                .originalQuestion(snapshot.originalQuestion())
                .currentStep(snapshot.nextStep())
                .maxSteps(snapshot.maxSteps())
                .deadlineAt(snapshot.deadlineAt())
                .steps(toAgentSteps(snapshot.steps()))
                .historySnapshot(toChatMessages(snapshot.historySnapshot()))
                .planningRecoveryAttempts(readNonNegativeInt(
                        snapshot.recoveryContext(),
                        PLANNING_RECOVERY_ATTEMPTS))
                .finalAnswer(snapshot.recoveryContext().get(FINAL_ANSWER))
                .stopReason(readEnum(
                        snapshot.recoveryContext(),
                        STOP_REASON,
                        AgentStopReason.class))
                .failureType(readEnum(
                        snapshot.recoveryContext(),
                        FAILURE_TYPE,
                        AgentFailureType.class))
                .errorMessage(snapshot.recoveryContext().get(ERROR_MESSAGE))
                .build();
    }

    private List<StepSnapshot> toStepSnapshots(List<AgentStep> steps) {
        if (steps == null) {
            return List.of();
        }

        List<StepSnapshot> snapshots = new ArrayList<>(steps.size());
        for (AgentStep step : steps) {
            if (step == null || step.getAction() == null
                    || step.getObservation() == null) {
                throw new AgentTaskSnapshotMappingException(
                        "only completed steps can be checkpointed");
            }

            AgentAction action = step.getAction();
            AgentObservation observation = step.getObservation();
            if (observation.getActionType() != action.getType()) {
                throw new AgentTaskSnapshotMappingException(
                        "step action and observation types must match");
            }

            snapshots.add(new StepSnapshot(
                    step.getStepIndex(),
                    action.getType(),
                    action.getThought(),
                    toJsonObject(action.getArguments()),
                    observation.getContent(),
                    toJsonObject(observation.getMetadata()),
                    observation.isSuccess(),
                    observation.getErrorMessage()));
        }
        return List.copyOf(snapshots);
    }

    private List<MessageSnapshot> toMessageSnapshots(
            List<ChatMessage> messages
    ) {
        if (messages == null) {
            return List.of();
        }

        return messages.stream()
                .map(message -> {
                    if (message == null) {
                        throw new AgentTaskSnapshotMappingException(
                                "history message cannot be null");
                    }
                    return new MessageSnapshot(
                            message.getRole(),
                            message.getContent());
                })
                .toList();
    }

    private Map<String, String> toRecoveryContext(AgentState state) {
        if (state.getPlanningRecoveryAttempts() < 0) {
            throw new AgentTaskSnapshotMappingException(
                    "planningRecoveryAttempts cannot be negative");
        }

        Map<String, String> context = new LinkedHashMap<>();
        context.put(
                PLANNING_RECOVERY_ATTEMPTS,
                Integer.toString(state.getPlanningRecoveryAttempts()));
        putIfNotNull(context, FINAL_ANSWER, state.getFinalAnswer());
        putIfNotNull(
                context,
                STOP_REASON,
                enumName(state.getStopReason()));
        putIfNotNull(
                context,
                FAILURE_TYPE,
                enumName(state.getFailureType()));
        putIfNotNull(context, ERROR_MESSAGE, state.getErrorMessage());
        return Map.copyOf(context);
    }

    private List<AgentStep> toAgentSteps(List<StepSnapshot> snapshots) {
        List<AgentStep> steps = new ArrayList<>(snapshots.size());
        for (StepSnapshot snapshot : snapshots) {
            AgentAction action = AgentAction.builder()
                    .type(snapshot.actionType())
                    .thought(snapshot.thought())
                    .arguments(fromJsonObject(
                            snapshot.actionArgumentsJson(),
                            "actionArgumentsJson"))
                    .build();
            AgentObservation observation = AgentObservation.builder()
                    .actionType(snapshot.actionType())
                    .content(snapshot.observationContent())
                    .metadata(fromJsonObject(
                            snapshot.observationMetadataJson(),
                            "observationMetadataJson"))
                    .success(snapshot.success())
                    .errorMessage(snapshot.errorMessage())
                    .build();
            steps.add(AgentStep.builder()
                    .stepIndex(snapshot.stepIndex())
                    .action(action)
                    .observation(observation)
                    .build());
        }
        return steps;
    }

    private List<ChatMessage> toChatMessages(
            List<MessageSnapshot> snapshots
    ) {
        return snapshots.stream()
                .map(snapshot -> new ChatMessage(
                        snapshot.role(),
                        snapshot.content()))
                .toList();
    }

    private String toJsonObject(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(
                    value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new AgentTaskSnapshotMappingException(
                    "step data cannot be serialized to JSON",
                    exception);
        }
    }

    private Map<String, Object> fromJsonObject(
            String json,
            String fieldName
    ) {
        try {
            Map<String, Object> value = objectMapper.readValue(
                    json,
                    STRING_OBJECT_MAP);
            if (value == null) {
                throw new AgentTaskSnapshotMappingException(
                        fieldName + " must contain a JSON object");
            }
            return new LinkedHashMap<>(value);
        } catch (JsonProcessingException exception) {
            throw new AgentTaskSnapshotMappingException(
                    fieldName + " must contain a valid JSON object",
                    exception);
        }
    }

    private int readNonNegativeInt(
            Map<String, String> context,
            String key
    ) {
        String value = context.getOrDefault(key, "0");
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative value");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new AgentTaskSnapshotMappingException(
                    "invalid recovery context value for " + key,
                    exception);
        }
    }

    private <E extends Enum<E>> E readEnum(
            Map<String, String> context,
            String key,
            Class<E> enumType
    ) {
        String value = context.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw new AgentTaskSnapshotMappingException(
                    "invalid recovery context value for " + key,
                    exception);
        }
    }

    private void putIfNotNull(
            Map<String, String> context,
            String key,
            String value
    ) {
        if (value != null) {
            context.put(key, value);
        }
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
