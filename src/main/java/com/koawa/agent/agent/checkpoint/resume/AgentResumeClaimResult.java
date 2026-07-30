package com.koawa.agent.agent.checkpoint.resume;

import java.util.Objects;

/**
 * Exhaustive result of coordinating one Resume request.
 */
public sealed interface AgentResumeClaimResult
        permits AgentResumeClaimResult.Claimed,
        AgentResumeClaimResult.Rejected,
        AgentResumeClaimResult.Recovered {

    /**
     * The caller may enter the Agent loop through this owned context.
     */
    record Claimed(AgentClaimedExecution execution)
            implements AgentResumeClaimResult {

        public Claimed {
            Objects.requireNonNull(
                    execution,
                    "execution cannot be null"
            );
        }
    }

    /**
     * The persisted task state does not allow this Resume command.
     */
    record Rejected(AgentResumeResult decision)
            implements AgentResumeClaimResult {

        public Rejected {
            Objects.requireNonNull(decision, "decision cannot be null");
            if (decision.accepted()) {
                throw new IllegalArgumentException(
                        "a rejected claim requires a rejected decision"
                );
            }
        }
    }

    /**
     * Recovery repaired a terminal boundary, so no loop should be entered.
     */
    record Recovered(AgentSnapshotRecoveryResult recovery)
            implements AgentResumeClaimResult {

        public Recovered {
            Objects.requireNonNull(recovery, "recovery cannot be null");
            if (recovery.shouldContinue()) {
                throw new IllegalArgumentException(
                        "a recovered-only result cannot continue execution"
                );
            }
        }
    }
}
