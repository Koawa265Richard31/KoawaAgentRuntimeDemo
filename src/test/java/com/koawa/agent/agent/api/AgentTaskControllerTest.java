package com.koawa.agent.agent.api;

import com.koawa.agent.agent.checkpoint.query.AgentTaskQueryService;
import com.koawa.agent.agent.checkpoint.query.AgentTaskView;
import com.koawa.agent.agent.checkpoint.query.AgentTaskView.PendingInterruptView;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AgentTaskControllerTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-01T01:00:00Z");

    private AgentTaskQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(AgentTaskQueryService.class);
        mockMvc = standaloneSetup(
                new AgentTaskController(queryService)
        ).build();
    }

    @Test
    void shouldReturnTaskControlPlaneViewWithoutSnapshotInternals()
            throws Exception {
        when(queryService.findByTaskId("task-1"))
                .thenReturn(Optional.of(waitingTask()));

        mockMvc.perform(get("/api/agent/v1/tasks/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.conversationId")
                        .value("conversation-1"))
                .andExpect(jsonPath("$.revision").value(7))
                .andExpect(jsonPath("$.status")
                        .value("WAITING_FOR_INPUT"))
                .andExpect(jsonPath("$.pendingInterrupt.interruptId")
                        .value("interrupt-7"))
                .andExpect(jsonPath("$.pendingInterrupt.type")
                        .value("USER_INPUT"))
                .andExpect(jsonPath("$.pendingInterrupt.prompt")
                        .value("Please provide a module name"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.originalQuestion").doesNotExist())
                .andExpect(jsonPath("$.steps").doesNotExist())
                .andExpect(jsonPath("$.historySnapshot").doesNotExist())
                .andExpect(jsonPath("$.recoveryContext").doesNotExist())
                .andExpect(jsonPath("$.pendingInterrupt.context")
                        .doesNotExist());

        verify(queryService).findByTaskId("task-1");
    }

    @Test
    void shouldReturnNotFoundForMissingTask() throws Exception {
        when(queryService.findByTaskId("missing"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/agent/v1/tasks/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnConversationTasksInServiceOrder() throws Exception {
        when(queryService.listByConversationId("conversation-1"))
                .thenReturn(List.of(
                        waitingTask(),
                        runningTask()
                ));

        mockMvc.perform(get(
                        "/api/agent/v1/conversations/conversation-1/tasks"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].taskId").value("task-1"))
                .andExpect(jsonPath("$[0].pendingInterrupt.interruptId")
                        .value("interrupt-7"))
                .andExpect(jsonPath("$[1].taskId").value("task-2"))
                .andExpect(jsonPath("$[1].status").value("RUNNING"));

        verify(queryService).listByConversationId("conversation-1");
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
                        "Please provide a module name",
                        CREATED_AT.plusSeconds(60)
                ),
                CREATED_AT,
                CREATED_AT.plusSeconds(120)
        );
    }

    private AgentTaskView runningTask() {
        return new AgentTaskView(
                "task-2",
                "conversation-1",
                1,
                AgentTaskStatus.RUNNING,
                1,
                8,
                CREATED_AT.plusSeconds(600),
                null,
                CREATED_AT,
                CREATED_AT.plusSeconds(180)
        );
    }
}
