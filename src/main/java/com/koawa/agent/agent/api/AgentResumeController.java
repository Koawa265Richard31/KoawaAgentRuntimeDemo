package com.koawa.agent.agent.api;

import com.koawa.agent.agent.checkpoint.query.AgentTaskQueryService;
import com.koawa.agent.agent.checkpoint.query.AgentTaskView;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeCommand;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeExecutionResult;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeExecutionService;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/agent/v1")
public final class AgentResumeController {

    private final AgentResumeExecutionService executionService;
    private final AgentTaskQueryService queryService;

    public AgentResumeController(
            AgentResumeExecutionService executionService,
            AgentTaskQueryService queryService
    ) {
        this.executionService = Objects.requireNonNull(
                executionService,
                "executionService cannot be null"
        );
        this.queryService = Objects.requireNonNull(
                queryService,
                "queryService cannot be null"
        );
    }

    @PostMapping("/tasks/{taskId}/resume")
    public ResponseEntity<AgentResumeResponse> resume(
            @PathVariable String taskId,
            @Valid @RequestBody ResumeRequest request
    ) {
        AgentResumeExecutionResult result = executionService.resume(
                new AgentResumeCommand(
                        taskId,
                        request.expectedRevision(),
                        request.interruptId(),
                        request.userInput()
                )
        );
        AgentTaskView task = queryService.findByTaskId(taskId)
                .orElseThrow(() -> new CheckpointNotFoundException(taskId));

        if (result instanceof AgentResumeExecutionResult.Executed executed) {
            AgentResumeResponse response =
                    new AgentResumeResponse.Executed(
                            task,
                            executed.runResult()
                    );
            return ResponseEntity.ok(response);
        }
        if (result instanceof AgentResumeExecutionResult.Rejected rejected) {
            AgentResumeResponse response =
                    new AgentResumeResponse.Rejected(
                            task,
                            AgentResumeResponse.RejectionReason.from(
                                    rejected.decision().rejectionReason()
                            )
                    );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        AgentResumeExecutionResult.Recovered recovered =
                (AgentResumeExecutionResult.Recovered) result;
        AgentResumeResponse response = new AgentResumeResponse.Recovered(
                task,
                AgentResumeResponse.RecoveryOutcome.from(
                        recovered.recovery().outcome()
                )
        );
        return ResponseEntity.ok(response);
    }

    public record ResumeRequest(
            @NotNull @PositiveOrZero Long expectedRevision,
            String interruptId,
            String userInput
    ) {
    }
}
