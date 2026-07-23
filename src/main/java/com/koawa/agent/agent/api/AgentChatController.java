package com.koawa.agent.agent.api;

import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.service.AgentChatFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/v1")
public final class AgentChatController {

    private final AgentChatFacade chatFacade;

    public AgentChatController(AgentChatFacade chatFacade) {
        this.chatFacade = chatFacade;
    }

    @PostMapping("/chat")
    public AgentRunResult chat(@Valid @RequestBody ChatRequest request) {
        return chatFacade.chat(
                request.question(),
                request.conversationId(),
                request.taskId(),
                request.userId()
        );
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String taskId) {
        chatFacade.cancel(taskId);
        return ResponseEntity.accepted().build();
    }

    public record ChatRequest(
            @NotBlank String question,
            String conversationId,
            String taskId,
            String userId
    ) {
    }
}
