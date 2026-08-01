package com.koawa.agent.agent.api;

import com.koawa.agent.agent.checkpoint.query.AgentTaskQueryService;
import com.koawa.agent.agent.checkpoint.query.AgentTaskView;
import com.koawa.agent.agent.checkpoint.query.AgentTaskView.PendingInterruptView;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeCommand;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeExecutionResult;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeExecutionService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeResult;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryResult;
import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AgentResumeControllerTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-01T02:00:00Z");

    private AgentResumeExecutionService executionService;
    private AgentTaskQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        executionService = mock(AgentResumeExecutionService.class);
        queryService = mock(AgentTaskQueryService.class);
        mockMvc = standaloneSetup(new AgentResumeController(
                executionService,
                queryService
        ))
                .setControllerAdvice(new AgentApiExceptionHandler())
                .build();
    }

    @Test
    void shouldExecuteResumeAndReturnAuthoritativeTaskView()
            throws Exception {
        AgentResumeCommand command = new AgentResumeCommand(
                "task-1",
                7,
                "interrupt-7",
                "module-a"
        );
        AgentRunResult runResult = new AgentRunResult(
                "conversation-1",
                "task-1",
                AgentStopReason.FINAL_ANSWER,
                3,
                0,
                null,
                "Completed module-a",
                null
        );
        when(executionService.resume(command)).thenReturn(
                new AgentResumeExecutionResult.Executed(runResult)
        );
        when(queryService.findByTaskId("task-1"))
                .thenReturn(Optional.of(completedTask(9)));

        mockMvc.perform(post("/api/agent/v1/tasks/task-1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 7,
                                  "interruptId": "interrupt-7",
                                  "userInput": "module-a"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("EXECUTED"))
                .andExpect(jsonPath("$.task.taskId").value("task-1"))
                .andExpect(jsonPath("$.task.revision").value(9))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.runResult.stopReason")
                        .value("FINAL_ANSWER"))
                .andExpect(jsonPath("$.runResult.content")
                        .value("Completed module-a"))
                .andExpect(jsonPath("$.rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.recoveryOutcome").doesNotExist());

        verify(executionService).resume(command);
        verify(queryService).findByTaskId("task-1");
    }

    @Test
    void shouldReturnConflictForRejectedResume() throws Exception {
        AgentResumeCommand command = new AgentResumeCommand(
                "task-1",
                7,
                "wrong-interrupt",
                "module-a"
        );
        AgentResumeResult decision = AgentResumeResult.rejected(
                "task-1",
                7,
                AgentTaskStatus.WAITING_FOR_INPUT,
                AgentResumeResult.RejectionReason.INTERRUPT_ID_MISMATCH
        );
        when(executionService.resume(command)).thenReturn(
                new AgentResumeExecutionResult.Rejected(decision)
        );
        when(queryService.findByTaskId("task-1"))
                .thenReturn(Optional.of(waitingTask()));

        mockMvc.perform(post("/api/agent/v1/tasks/task-1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 7,
                                  "interruptId": "wrong-interrupt",
                                  "userInput": "module-a"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.task.status")
                        .value("WAITING_FOR_INPUT"))
                .andExpect(jsonPath("$.rejectionReason")
                        .value("INTERRUPT_ID_MISMATCH"))
                .andExpect(jsonPath("$.runResult").doesNotExist())
                .andExpect(jsonPath("$.recoveryOutcome").doesNotExist());

        verify(executionService).resume(command);
    }

    @Test
    void shouldReturnRecoveredTaskWithoutRunResult() throws Exception {
        AgentSnapshotRecoveryResult recovery =
                mock(AgentSnapshotRecoveryResult.class);
        when(recovery.shouldContinue()).thenReturn(false);
        when(recovery.outcome()).thenReturn(
                AgentSnapshotRecoveryResult.Outcome
                        .TERMINAL_STEP_REPAIRED
        );
        AgentResumeCommand command = new AgentResumeCommand(
                "task-1",
                7,
                null,
                null
        );
        AgentResumeExecutionResult result =
                new AgentResumeExecutionResult.Recovered(recovery);
        when(executionService.resume(command)).thenReturn(result);
        when(queryService.findByTaskId("task-1"))
                .thenReturn(Optional.of(completedTask(8)));

        mockMvc.perform(post("/api/agent/v1/tasks/task-1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECOVERED"))
                .andExpect(jsonPath("$.task.revision").value(8))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recoveryOutcome")
                        .value("TERMINAL_STEP_REPAIRED"))
                .andExpect(jsonPath("$.runResult").doesNotExist())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist());

        verify(executionService).resume(command);
    }

    @Test
    void shouldRejectMissingOrNegativeExpectedRevision() throws Exception {
        mockMvc.perform(post("/api/agent/v1/tasks/task-1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field")
                        .value("expectedRevision"));
        mockMvc.perform(post("/api/agent/v1/tasks/task-1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field")
                        .value("expectedRevision"));

        verifyNoInteractions(executionService, queryService);
    }

    @Test
    void shouldReturnMalformedRequestWithoutInternalParserDetails()
            throws Exception {
        mockMvc.perform(post("/api/agent/v1/tasks/task-1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Request body is missing or malformed"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.cause").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());

        verifyNoInteractions(executionService, queryService);
    }

    @Test
    void shouldHideFencingIdentityWhenExecutionLeaseIsLost()
            throws Exception {
        AgentResumeCommand command = new AgentResumeCommand(
                "task-1",
                7,
                null,
                null
        );
        when(executionService.resume(command)).thenThrow(
                new AgentExecutionLeaseLostException(
                        "task-1",
                        42,
                        AgentExecutionLeaseLostException.Reason
                                .OWNER_OR_TOKEN_MISMATCH
                )
        );

        mockMvc.perform(post("/api/agent/v1/tasks/task-1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("EXECUTION_LEASE_LOST"))
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fencingToken").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.cause").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());

        verifyNoInteractions(queryService);
    }

    @Test
    void shouldRejectContradictoryPublicResponseVariants() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentResumeResponse.Rejected(
                        AgentResumeResponse.Outcome.EXECUTED,
                        completedTask(9),
                        AgentResumeResponse.RejectionReason.TERMINAL_STATUS
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentResumeResponse.Recovered(
                        AgentResumeResponse.Outcome.REJECTED,
                        completedTask(9),
                        AgentResumeResponse.RecoveryOutcome
                                .TERMINAL_STEP_REPAIRED
                )
        );
    }

    private AgentTaskView completedTask(long revision) {
        return new AgentTaskView(
                "task-1",
                "conversation-1",
                revision,
                AgentTaskStatus.COMPLETED,
                3,
                8,
                CREATED_AT.plusSeconds(600),
                null,
                CREATED_AT,
                CREATED_AT.plusSeconds(300)
        );
    }

    private AgentTaskView waitingTask() {
        return new AgentTaskView(
                "task-1",
                "conversation-1",
                7,
                AgentTaskStatus.WAITING_FOR_INPUT,
                2,
                8,
                CREATED_AT.plusSeconds(600),
                new PendingInterruptView(
                        "interrupt-7",
                        InterruptType.USER_INPUT,
                        "Which module?",
                        CREATED_AT.plusSeconds(120)
                ),
                CREATED_AT,
                CREATED_AT.plusSeconds(240)
        );
    }
}
