package com.koawa.agent.agent.api;

import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.service.AgentChatFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AgentChatControllerTest {

    private AgentChatFacade chatFacade;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        chatFacade = mock(AgentChatFacade.class);
        mockMvc = standaloneSetup(new AgentChatController(chatFacade))
                .setControllerAdvice(new AgentApiExceptionHandler())
                .build();
    }

    @Test
    void shouldPreserveChatRequestAndResponseContract() throws Exception {
        when(chatFacade.chat(
                "question",
                "conversation-1",
                "task-1",
                "user-1"
        )).thenReturn(new AgentRunResult(
                "conversation-1",
                "task-1",
                AgentStopReason.FINAL_ANSWER,
                2,
                0,
                null,
                "answer",
                null
        ));

        mockMvc.perform(post("/api/agent/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "question",
                                  "conversationId": "conversation-1",
                                  "taskId": "task-1",
                                  "userId": "user-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId")
                        .value("conversation-1"))
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.stopReason")
                        .value("FINAL_ANSWER"))
                .andExpect(jsonPath("$.stepCount").value(2))
                .andExpect(jsonPath("$.planningRecoveryAttempts")
                        .value(0))
                .andExpect(jsonPath("$.content").value("answer"))
                .andExpect(jsonPath("$.failureType").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        verify(chatFacade).chat(
                "question",
                "conversation-1",
                "task-1",
                "user-1"
        );
    }

    @Test
    void shouldRejectBlankQuestionWithoutCallingFacade() throws Exception {
        mockMvc.perform(post("/api/agent/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field")
                        .value("question"));

        verifyNoInteractions(chatFacade);
    }

    @Test
    void shouldPreserveAcceptedCancelContract() throws Exception {
        mockMvc.perform(post("/api/agent/v1/tasks/task-1/cancel"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(chatFacade).cancel("task-1");
    }
}
