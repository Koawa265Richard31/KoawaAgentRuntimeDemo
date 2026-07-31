package com.koawa.agent.agent.api;

import com.koawa.agent.agent.checkpoint.query.AgentTaskQueryService;
import com.koawa.agent.agent.checkpoint.query.AgentTaskView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/agent/v1")
public final class AgentTaskController {

    private final AgentTaskQueryService queryService;

    public AgentTaskController(AgentTaskQueryService queryService) {
        this.queryService = Objects.requireNonNull(
                queryService,
                "queryService cannot be null"
        );
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<AgentTaskView> getTask(
            @PathVariable String taskId
    ) {
        return queryService.findByTaskId(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/conversations/{conversationId}/tasks")
    public List<AgentTaskView> listConversationTasks(
            @PathVariable String conversationId
    ) {
        return queryService.listByConversationId(conversationId);
    }
}
