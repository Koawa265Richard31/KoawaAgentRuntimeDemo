package com.koawa.agent.agent.api;

import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentCheckpointLifecycleException;
import com.koawa.agent.agent.exception.AgentExecutionConflictException;
import com.koawa.agent.agent.exception.AgentInterruptConsumptionException;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import com.koawa.agent.agent.exception.CorruptedCheckpointException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentApiExceptionHandlerTest {

    private final AgentApiExceptionHandler handler =
            new AgentApiExceptionHandler();

    @Test
    void shouldMapNotFoundAndRevisionConflict() {
        ResponseEntity<AgentApiError> notFound =
                handler.handleCheckpointNotFound(
                        new CheckpointNotFoundException("task-1")
                );

        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        assertEquals(
                AgentApiError.Code.TASK_NOT_FOUND,
                body(notFound).code()
        );
        assertFalse(body(notFound).retryable());

        ResponseEntity<AgentApiError> conflict =
                handler.handleCheckpointConflict(
                        new CheckpointConflictException(
                                "task-1",
                                7,
                                8L
                        )
                );

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(
                AgentApiError.Code.CHECKPOINT_REVISION_CONFLICT,
                body(conflict).code()
        );
        assertEquals(7L, body(conflict).expectedRevision());
        assertEquals(8L, body(conflict).actualRevision());
        assertTrue(body(conflict).retryable());
    }

    @Test
    void shouldExposeRetryTimeWithoutExecutionOwner() {
        Instant retryAt = Instant.parse("2026-08-01T03:00:30Z");

        ResponseEntity<AgentApiError> response =
                handler.handleExecutionConflict(
                        new AgentExecutionConflictException(
                                "task-1",
                                retryAt
                        )
                );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(
                AgentApiError.Code.TASK_EXECUTION_CONFLICT,
                body(response).code()
        );
        assertEquals("task-1", body(response).taskId());
        assertEquals(retryAt, body(response).retryAt());
        assertTrue(body(response).retryable());
    }

    @Test
    void shouldMapInterruptConflictAsRecoverableClientConflict() {
        ResponseEntity<AgentApiError> response =
                handler.handleInterruptConflict(
                        new AgentInterruptConsumptionException(
                                "task-1",
                                AgentTaskStatus.WAITING_FOR_INPUT,
                                AgentInterruptConsumptionException.Reason
                                        .INTERRUPT_ID_MISMATCH
                        )
                );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(
                AgentApiError.Code.INTERRUPT_CONSUMPTION_CONFLICT,
                body(response).code()
        );
        assertTrue(body(response).retryable());
        assertNull(body(response).retryAt());
    }

    @Test
    void shouldMarkUnsafePersistenceFailuresAsNotRetryable() {
        ResponseEntity<AgentApiError> lifecycle =
                handler.handleCheckpointLifecycle(
                        new AgentCheckpointLifecycleException(
                                "private persistence detail",
                                new RuntimeException("database secret")
                        )
                );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                lifecycle.getStatusCode()
        );
        assertEquals(
                AgentApiError.Code.CHECKPOINT_PERSISTENCE_FAILED,
                body(lifecycle).code()
        );
        assertFalse(body(lifecycle).retryable());
        assertFalse(body(lifecycle).message().contains("private"));
        assertFalse(body(lifecycle).message().contains("secret"));

        ResponseEntity<AgentApiError> corrupted =
                handler.handleCorruptedCheckpoint(
                        new CorruptedCheckpointException(
                                "task-1",
                                "private corrupt JSON"
                        )
                );
        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                corrupted.getStatusCode()
        );
        assertEquals(
                AgentApiError.Code.CHECKPOINT_CORRUPTED,
                body(corrupted).code()
        );
        assertFalse(body(corrupted).message().contains("private"));
    }

    @Test
    void shouldReturnSanitizedFallbackForUnexpectedFailure() {
        ResponseEntity<AgentApiError> response =
                handler.handleUnexpected(
                        new RuntimeException("private secret")
                );

        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode()
        );
        assertEquals(
                AgentApiError.Code.INTERNAL_ERROR,
                body(response).code()
        );
        assertEquals(
                "An unexpected internal error occurred",
                body(response).message()
        );
        assertFalse(body(response).message().contains("secret"));
        assertFalse(body(response).retryable());
    }

    private AgentApiError body(ResponseEntity<AgentApiError> response) {
        return response.getBody();
    }
}
