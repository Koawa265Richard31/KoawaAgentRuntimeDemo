package com.koawa.agent.agent.api;

import com.koawa.agent.agent.api.AgentApiError.Code;
import com.koawa.agent.agent.api.AgentApiError.FieldViolation;
import com.koawa.agent.agent.exception.AgentCheckpointLifecycleException;
import com.koawa.agent.agent.exception.AgentExecutionConflictException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentInterruptConsumptionException;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import com.koawa.agent.agent.exception.CorruptedCheckpointException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;

/**
 * Converts internal Agent failures into stable, sanitized HTTP errors.
 */
@Slf4j
@RestControllerAdvice
public final class AgentApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AgentApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception
    ) {
        List<FieldViolation> violations = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldViolation(
                        error.getField(),
                        error.getDefaultMessage() == null
                                ? "invalid value"
                                : error.getDefaultMessage()
                ))
                .distinct()
                .sorted(Comparator
                        .comparing(FieldViolation::field)
                        .thenComparing(FieldViolation::message))
                .toList();
        return response(
                HttpStatus.BAD_REQUEST,
                AgentApiError.requestError(
                        Code.VALIDATION_FAILED,
                        "Request validation failed",
                        violations
                )
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<AgentApiError> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        List<FieldViolation> violations = exception
                .getConstraintViolations()
                .stream()
                .map(violation -> new FieldViolation(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()
                ))
                .distinct()
                .sorted(Comparator
                        .comparing(FieldViolation::field)
                        .thenComparing(FieldViolation::message))
                .toList();
        return response(
                HttpStatus.BAD_REQUEST,
                AgentApiError.requestError(
                        Code.VALIDATION_FAILED,
                        "Request validation failed",
                        violations
                )
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AgentApiError> handleMessageNotReadable(
            HttpMessageNotReadableException exception
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                AgentApiError.requestError(
                        Code.MALFORMED_REQUEST,
                        "Request body is missing or malformed",
                        List.of()
                )
        );
    }

    @ExceptionHandler(CheckpointNotFoundException.class)
    public ResponseEntity<AgentApiError> handleCheckpointNotFound(
            CheckpointNotFoundException exception
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                AgentApiError.taskError(
                        Code.TASK_NOT_FOUND,
                        "Task checkpoint was not found",
                        exception.getTaskId(),
                        false
                )
        );
    }

    @ExceptionHandler(CheckpointConflictException.class)
    public ResponseEntity<AgentApiError> handleCheckpointConflict(
            CheckpointConflictException exception
    ) {
        return response(
                HttpStatus.CONFLICT,
                AgentApiError.revisionConflict(
                        exception.getTaskId(),
                        exception.getExpectedRevision(),
                        exception.getActualRevision()
                )
        );
    }

    @ExceptionHandler(AgentExecutionConflictException.class)
    public ResponseEntity<AgentApiError> handleExecutionConflict(
            AgentExecutionConflictException exception
    ) {
        return response(
                HttpStatus.CONFLICT,
                AgentApiError.executionConflict(
                        exception.getTaskId(),
                        exception.getRetryAt()
                )
        );
    }

    @ExceptionHandler(AgentInterruptConsumptionException.class)
    public ResponseEntity<AgentApiError> handleInterruptConflict(
            AgentInterruptConsumptionException exception
    ) {
        return response(
                HttpStatus.CONFLICT,
                AgentApiError.taskError(
                        Code.INTERRUPT_CONSUMPTION_CONFLICT,
                        "User input no longer matches the current interrupt",
                        exception.getTaskId(),
                        true
                )
        );
    }

    @ExceptionHandler(AgentExecutionLeaseLostException.class)
    public ResponseEntity<AgentApiError> handleExecutionLeaseLost(
            AgentExecutionLeaseLostException exception
    ) {
        return response(
                HttpStatus.CONFLICT,
                AgentApiError.taskError(
                        Code.EXECUTION_LEASE_LOST,
                        "Execution ownership was lost; reload task state",
                        exception.getTaskId(),
                        false
                )
        );
    }

    @ExceptionHandler(AgentCheckpointLifecycleException.class)
    public ResponseEntity<AgentApiError> handleCheckpointLifecycle(
            AgentCheckpointLifecycleException exception
    ) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                AgentApiError.taskError(
                        Code.CHECKPOINT_PERSISTENCE_FAILED,
                        "Task state could not be persisted safely",
                        null,
                        false
                )
        );
    }

    @ExceptionHandler(CorruptedCheckpointException.class)
    public ResponseEntity<AgentApiError> handleCorruptedCheckpoint(
            CorruptedCheckpointException exception
    ) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                AgentApiError.taskError(
                        Code.CHECKPOINT_CORRUPTED,
                        "Stored task state is corrupted",
                        exception.getTaskId(),
                        false
                )
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentApiError> handleUnexpected(
            Exception exception
    ) {
        log.error(
                "Unhandled Agent API exception type={}",
                exception.getClass().getName()
        );
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                AgentApiError.taskError(
                        Code.INTERNAL_ERROR,
                        "An unexpected internal error occurred",
                        null,
                        false
                )
        );
    }

    private ResponseEntity<AgentApiError> response(
            HttpStatus status,
            AgentApiError error
    ) {
        return ResponseEntity.status(status).body(error);
    }
}
