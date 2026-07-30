package com.koawa.agent.agent.checkpoint.resume;

import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;

import java.util.Objects;

/**
 * Successful result of consuming one USER_INPUT interrupt.
 */
public record AgentInterruptConsumptionResult(
        String interruptId,
        AgentTaskSnapshot snapshot,
        AgentState state
) {

    public AgentInterruptConsumptionResult {
        if (interruptId == null || interruptId.isBlank()) {
            throw new IllegalArgumentException(
                    "interruptId must be a non-blank string"
            );
        }
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        Objects.requireNonNull(state, "state cannot be null");

        if (snapshot.status() != AgentTaskStatus.RUNNING) {
            throw new IllegalArgumentException(
                    "a consumed interrupt must produce a RUNNING snapshot"
            );
        }
        if (snapshot.pendingInterrupt() != null) {
            throw new IllegalArgumentException(
                    "a consumed interrupt cannot remain pending"
            );
        }
        if (!snapshot.taskId().equals(state.getTaskId())) {
            throw new IllegalArgumentException(
                    "snapshot and state taskId must match"
            );
        }
        if (snapshot.nextStep() != state.getCurrentStep()) {
            throw new IllegalArgumentException(
                    "snapshot nextStep and state currentStep must match"
            );
        }
    }
}
