package com.koawa.agent.agent.service;

import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.runtime.AgentTaskCancellationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public final class AgentChatFacade {

    private final AgentChatService agentChatService;
    private final AgentTaskCancellationRegistry cancellationRegistry;

    public AgentChatFacade(
            AgentChatService agentChatService,
            AgentTaskCancellationRegistry cancellationRegistry
    ) {
        this.agentChatService = Objects.requireNonNull(
                agentChatService,
                "agentChatService cannot be null"
        );
        this.cancellationRegistry = Objects.requireNonNull(
                cancellationRegistry,
                "cancellationRegistry cannot be null"
        );
    }

    public AgentRunResult chat(
            String question,
            String conversationId,
            String taskId,
            String userId
    ) {
        long startedAt = System.nanoTime();
        AgentRunResult result = agentChatService.chat(
                question,
                conversationId,
                taskId,
                userId
        );
        cancellationRegistry.clear(result.taskId());
        log.info(
                "agent_run conversationId={}, taskId={}, stopReason={}, "
                        + "steps={}, recoveryAttempts={}, elapsedMs={}",
                result.conversationId(),
                result.taskId(),
                result.stopReason(),
                result.stepCount(),
                result.planningRecoveryAttempts(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        );
        return result;
    }

    public void cancel(String taskId) {
        cancellationRegistry.cancel(taskId);
    }

}
