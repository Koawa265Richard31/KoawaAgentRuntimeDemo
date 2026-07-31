package com.koawa.agent.agent.checkpoint.resume;

import com.koawa.agent.agent.domain.AgentRunResult;

import java.util.Objects;

/**
 * Exhaustive result after a Resume request is either run or stopped before
 * entering the Agent loop.
 */
public sealed interface AgentResumeExecutionResult
        permits AgentResumeExecutionResult.Executed,
        AgentResumeExecutionResult.Rejected,
        AgentResumeExecutionResult.Recovered {

    /**
     * The Agent loop ran and produced a persisted run outcome.
     */
    record Executed(AgentRunResult runResult)
            implements AgentResumeExecutionResult {

        public Executed {
            Objects.requireNonNull(
                    runResult,
                    "runResult cannot be null"
            );
        }
    }

    /**
     * Resume eligibility rejected the command before a lease was acquired.
     */
    record Rejected(AgentResumeResult decision)
            implements AgentResumeExecutionResult {

        public Rejected {
            Objects.requireNonNull(decision, "decision cannot be null");
            if (decision.accepted()) {
                throw new IllegalArgumentException(
                        "a rejected execution requires a rejected decision"
                );
            }
        }
    }

    /**
     * Recovery repaired a terminal boundary without entering the Agent loop.
     */
    record Recovered(AgentSnapshotRecoveryResult recovery)
            implements AgentResumeExecutionResult {

        public Recovered {
            Objects.requireNonNull(recovery, "recovery cannot be null");
            if (recovery.shouldContinue()) {
                throw new IllegalArgumentException(
                        "a recovered-only execution cannot continue"
                );
            }
        }
    }
}
