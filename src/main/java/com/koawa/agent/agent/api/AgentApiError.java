package com.koawa.agent.agent.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Stable and sanitized HTTP error contract for Agent APIs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentApiError(
        Code code,
        String message,
        String taskId,
        boolean retryable,
        Instant retryAt,
        Long expectedRevision,
        Long actualRevision,
        List<FieldViolation> violations
) {

    public AgentApiError {
        Objects.requireNonNull(code, "code cannot be null");
        message = requireText(message, "message");
        if (taskId != null) {
            taskId = requireText(taskId, "taskId");
        }
        requireRevision(expectedRevision, "expectedRevision");
        requireRevision(actualRevision, "actualRevision");
        violations = List.copyOf(Objects.requireNonNull(
                violations,
                "violations cannot be null"
        ));
    }

    public static AgentApiError taskError(
            Code code,
            String message,
            String taskId,
            boolean retryable
    ) {
        return new AgentApiError(
                code,
                message,
                taskId,
                retryable,
                null,
                null,
                null,
                List.of()
        );
    }

    public static AgentApiError revisionConflict(
            String taskId,
            long expectedRevision,
            Long actualRevision
    ) {
        return new AgentApiError(
                Code.CHECKPOINT_REVISION_CONFLICT,
                "Task revision has changed; reload before resuming",
                taskId,
                actualRevision != null,
                null,
                expectedRevision,
                actualRevision,
                List.of()
        );
    }

    public static AgentApiError executionConflict(
            String taskId,
            Instant retryAt
    ) {
        return new AgentApiError(
                Code.TASK_EXECUTION_CONFLICT,
                "Another execution currently owns this task",
                taskId,
                true,
                Objects.requireNonNull(retryAt, "retryAt cannot be null"),
                null,
                null,
                List.of()
        );
    }

    public static AgentApiError requestError(
            Code code,
            String message,
            List<FieldViolation> violations
    ) {
        return new AgentApiError(
                code,
                message,
                null,
                false,
                null,
                null,
                null,
                violations
        );
    }

    public enum Code {
        VALIDATION_FAILED,
        MALFORMED_REQUEST,
        TASK_NOT_FOUND,
        CHECKPOINT_REVISION_CONFLICT,
        TASK_EXECUTION_CONFLICT,
        INTERRUPT_CONSUMPTION_CONFLICT,
        EXECUTION_LEASE_LOST,
        CHECKPOINT_PERSISTENCE_FAILED,
        CHECKPOINT_CORRUPTED,
        INTERNAL_ERROR
    }

    public record FieldViolation(
            String field,
            String message
    ) {

        public FieldViolation {
            field = requireText(field, "field");
            message = requireText(message, "message");
        }
    }

    private static void requireRevision(Long revision, String fieldName) {
        if (revision != null && revision < 0) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be negative"
            );
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
        return value.trim();
    }
}
