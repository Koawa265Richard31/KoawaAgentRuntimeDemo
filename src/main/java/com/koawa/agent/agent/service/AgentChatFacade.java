package com.koawa.agent.agent.service;

import com.koawa.agent.agent.domain.AgentConversationTurn;
import com.koawa.agent.agent.domain.AgentConversationTurnInput;
import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.runtime.AgentTaskCancellationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public final class AgentChatFacade {

    private final AgentChatService agentChatService;
    private final AgentConversationStore conversationStore;
    private final AgentTaskCancellationRegistry cancellationRegistry;

    public AgentChatFacade(
            AgentChatService agentChatService,
            AgentConversationStore conversationStore,
            AgentTaskCancellationRegistry cancellationRegistry
    ) {
        this.agentChatService = Objects.requireNonNull(
                agentChatService,
                "agentChatService cannot be null"
        );
        this.conversationStore = Objects.requireNonNull(
                conversationStore,
                "conversationStore cannot be null"
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
        if (isDeliverable(result)) {
            conversationStore.appendTurn(new AgentConversationTurn(
                    result.conversationId(),
                    userId,
                    result.taskId(),
                    terminalStepIndex(result),
                    AgentConversationTurnInput.originalQuestion(question),
                    outcome(result.stopReason()),
                    result.content()
            ));
        }
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

    private boolean isDeliverable(AgentRunResult result) {
        return (result.stopReason() == AgentStopReason.FINAL_ANSWER
                || result.stopReason() == AgentStopReason.ASK_CLARIFICATION)
                && result.content() != null
                && !result.content().isBlank();
    }

    private int terminalStepIndex(AgentRunResult result) {
        if (result.stepCount() <= 0) {
            throw new IllegalStateException(
                    "deliverable run must contain a terminal step"
            );
        }
        return result.stepCount() - 1;
    }

    private AgentConversationTurn.Outcome outcome(
            AgentStopReason stopReason
    ) {
        return switch (stopReason) {
            case FINAL_ANSWER ->
                    AgentConversationTurn.Outcome.FINAL_ANSWER;
            case ASK_CLARIFICATION ->
                    AgentConversationTurn.Outcome.ASK_CLARIFICATION;
            default -> throw new IllegalArgumentException(
                    "stop reason is not deliverable: " + stopReason
            );
        };
    }
}
