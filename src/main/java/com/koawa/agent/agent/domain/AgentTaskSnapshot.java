package com.koawa.agent.agent.domain;

import com.koawa.agent.framework.convention.ChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, serializable state required to resume an agent task at a step boundary.
 *
 * <p>The snapshot uses persistence-specific value objects instead of mutable runtime objects
 * such as {@link AgentState}, {@link AgentStep}, and {@link ChatMessage}.
 */
public record AgentTaskSnapshot(
        int schemaVersion,
        String taskId,
        String conversationId,
        String userId,
        long revision,
        AgentTaskStatus status,
        String originalQuestion,
        int nextStep,
        int maxSteps,
        Instant deadlineAt,
        List<StepSnapshot> steps,
        List<MessageSnapshot> historySnapshot,
        Map<String, String> recoveryContext,
        PendingInterrupt pendingInterrupt,
        Instant createdAt,
        Instant updatedAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AgentTaskSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException(
                    "schemaVersion must be positive"
            );
        }
        requireText(taskId, "taskId");
        requireText(conversationId, "conversationId");
        if (revision < 0) {
            throw new IllegalArgumentException(
                    "revision cannot be negative"
            );
        }
        Objects.requireNonNull(
                status,
                "status cannot be null"
        );
        requireText(originalQuestion, "originalQuestion");
        if (nextStep < 0) {
            throw new IllegalArgumentException(
                    "nextStep cannot be negative"
            );
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        if (nextStep > maxSteps) {
            throw new IllegalArgumentException("nextStep cannot exceed maxSteps");
        }

        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt cannot be null");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps cannot be null"));
        historySnapshot = List.copyOf(Objects.requireNonNull(
                historySnapshot,
                "historySnapshot cannot be null"));
        recoveryContext = Map.copyOf(Objects.requireNonNull(
                recoveryContext,
                "recoveryContext cannot be null"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null");

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
        if (steps.size() != nextStep) {
            throw new IllegalArgumentException("steps size must equal nextStep");
        }
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).stepIndex() != index) {
                throw new IllegalArgumentException(
                        "steps must be contiguous and ordered from index zero");
            }
        }

        validateInterrupt(status, pendingInterrupt);
    }

    private static void validateInterrupt(
            AgentTaskStatus status,
            PendingInterrupt pendingInterrupt
    ) {
        if (!status.isWaiting()) {
            if (pendingInterrupt != null) {
                throw new IllegalArgumentException(
                        "pendingInterrupt is only allowed for a waiting task");
            }
            return;
        }

        if (pendingInterrupt == null) {
            throw new IllegalArgumentException(
                    "a waiting task requires pendingInterrupt");
        }

        InterruptType expectedType =
                status == AgentTaskStatus.WAITING_FOR_INPUT
                        ? InterruptType.USER_INPUT
                        : InterruptType.APPROVAL;
        if (pendingInterrupt.type() != expectedType) {
            throw new IllegalArgumentException(
                    "pendingInterrupt type does not match task status");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
    }

    /**
     * Immutable representation of one completed agent step.
     *
     * <p>Structured arguments and metadata cross the persistence boundary as JSON strings.
     */
    public record StepSnapshot(
            int stepIndex,
            AgentActionType actionType,
            String thought,
            String actionArgumentsJson,
            String observationContent,
            String observationMetadataJson,
            boolean success,
            String errorMessage
    ) {

        public StepSnapshot {
            if (stepIndex < 0) {
                throw new IllegalArgumentException("stepIndex cannot be negative");
            }
            Objects.requireNonNull(actionType, "actionType cannot be null");
            requireText(actionArgumentsJson, "actionArgumentsJson");
            requireText(observationMetadataJson, "observationMetadataJson");
        }
    }

    /**
     * Immutable history entry independent from the mutable runtime {@link ChatMessage}.
     */
    public record MessageSnapshot(
            ChatMessage.Role role,
            String content
    ) {

        public MessageSnapshot {
            Objects.requireNonNull(role, "role cannot be null");
            Objects.requireNonNull(content, "content cannot be null");
        }
    }

    /**
     * Data required to present and later resolve a human interrupt.
     */
    public record PendingInterrupt(
            String interruptId,
            InterruptType type,
            String prompt,
            Map<String, String> context,
            Instant createdAt
    ) {

        public PendingInterrupt {
            requireText(interruptId, "interruptId");
            Objects.requireNonNull(type, "type cannot be null");
            requireText(prompt, "prompt");
            context = Map.copyOf(Objects.requireNonNull(
                    context,
                    "context cannot be null"));
            createdAt = Objects.requireNonNull(
                    createdAt,
                    "createdAt cannot be null");
        }
    }

    public enum InterruptType {
        USER_INPUT,
        APPROVAL
    }
}
