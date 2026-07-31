package com.koawa.agent.agent.checkpoint.query;

import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Public control-plane view of a persisted task.
 *
 * <p>The view deliberately excludes user identity, conversation history,
 * recovery metadata and step internals from the HTTP boundary.
 */
public record AgentTaskView(
        String taskId,
        String conversationId,
        long revision,
        AgentTaskStatus status,
        int nextStep,
        int maxSteps,
        Instant deadlineAt,
        PendingInterruptView pendingInterrupt,
        Instant createdAt,
        Instant updatedAt
) {

    public AgentTaskView {
        requireText(taskId, "taskId");
        requireText(conversationId, "conversationId");
        if (revision < 0) {
            throw new IllegalArgumentException(
                    "revision cannot be negative"
            );
        }
        Objects.requireNonNull(status, "status cannot be null");
        if (nextStep < 0) {
            throw new IllegalArgumentException(
                    "nextStep cannot be negative"
            );
        }
        if (maxSteps <= 0 || nextStep > maxSteps) {
            throw new IllegalArgumentException(
                    "maxSteps must be positive and not less than nextStep"
            );
        }
        Objects.requireNonNull(deadlineAt, "deadlineAt cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        if (status.isWaiting() != (pendingInterrupt != null)) {
            throw new IllegalArgumentException(
                    "pendingInterrupt must be present exactly for a waiting task"
            );
        }
        if (status == AgentTaskStatus.WAITING_FOR_INPUT
                && pendingInterrupt.type() != InterruptType.USER_INPUT) {
            throw new IllegalArgumentException(
                    "WAITING_FOR_INPUT requires a USER_INPUT interrupt"
            );
        }
        if (status == AgentTaskStatus.WAITING_FOR_APPROVAL
                && pendingInterrupt.type() != InterruptType.APPROVAL) {
            throw new IllegalArgumentException(
                    "WAITING_FOR_APPROVAL requires an APPROVAL interrupt"
            );
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "updatedAt cannot be before createdAt"
            );
        }
    }

    public static AgentTaskView from(AgentTaskSnapshot snapshot) {
        AgentTaskSnapshot actual = Objects.requireNonNull(
                snapshot,
                "snapshot cannot be null"
        );
        return new AgentTaskView(
                actual.taskId(),
                actual.conversationId(),
                actual.revision(),
                actual.status(),
                actual.nextStep(),
                actual.maxSteps(),
                actual.deadlineAt(),
                PendingInterruptView.from(actual.pendingInterrupt()),
                actual.createdAt(),
                actual.updatedAt()
        );
    }

    public record PendingInterruptView(
            String interruptId,
            InterruptType type,
            String prompt,
            Instant createdAt
    ) {

        public PendingInterruptView {
            requireText(interruptId, "interruptId");
            Objects.requireNonNull(type, "type cannot be null");
            requireText(prompt, "prompt");
            Objects.requireNonNull(
                    createdAt,
                    "createdAt cannot be null"
            );
        }

        private static PendingInterruptView from(
                PendingInterrupt interrupt
        ) {
            if (interrupt == null) {
                return null;
            }
            return new PendingInterruptView(
                    interrupt.interruptId(),
                    interrupt.type(),
                    interrupt.prompt(),
                    interrupt.createdAt()
            );
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be blank"
            );
        }
    }
}
