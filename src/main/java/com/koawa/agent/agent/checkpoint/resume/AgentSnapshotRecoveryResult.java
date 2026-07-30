package com.koawa.agent.agent.checkpoint.resume;

import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;

import java.util.Objects;

/**
 * Result of rebuilding runtime state from one persisted task snapshot.
 */
public record AgentSnapshotRecoveryResult(
        AgentTaskSnapshot snapshot,
        AgentState state,
        Outcome outcome
) {

    public AgentSnapshotRecoveryResult {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(outcome, "outcome cannot be null");

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

        boolean running = snapshot.status() == AgentTaskStatus.RUNNING;
        if ((outcome == Outcome.READY_TO_CONTINUE) != running) {
            throw new IllegalArgumentException(
                    "only a RUNNING snapshot can be ready to continue"
            );
        }
    }

    public boolean shouldContinue() {
        return outcome == Outcome.READY_TO_CONTINUE;
    }

    public enum Outcome {
        READY_TO_CONTINUE,
        TERMINAL_STEP_REPAIRED,
        NOT_RUNNING
    }
}
