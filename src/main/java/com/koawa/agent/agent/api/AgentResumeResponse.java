package com.koawa.agent.agent.api;

import com.koawa.agent.agent.checkpoint.query.AgentTaskView;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeResult;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryResult;
import com.koawa.agent.agent.domain.AgentRunResult;

import java.util.Objects;

/**
 * Public HTTP outcomes of one synchronous Resume request.
 */
public sealed interface AgentResumeResponse
        permits AgentResumeResponse.Executed,
        AgentResumeResponse.Rejected,
        AgentResumeResponse.Recovered {

    enum Outcome {
        EXECUTED,
        REJECTED,
        RECOVERED
    }

    enum RejectionReason {
        INTERRUPT_ID_REQUIRED,
        INTERRUPT_ID_MISMATCH,
        INTERRUPT_ID_NOT_APPLICABLE,
        APPROVAL_RESUME_NOT_SUPPORTED,
        TERMINAL_STATUS;

        static RejectionReason from(
                AgentResumeResult.RejectionReason reason
        ) {
            AgentResumeResult.RejectionReason actual =
                    Objects.requireNonNull(
                            reason,
                            "rejectionReason cannot be null"
                    );
            return switch (actual) {
                case INTERRUPT_ID_REQUIRED -> INTERRUPT_ID_REQUIRED;
                case INTERRUPT_ID_MISMATCH -> INTERRUPT_ID_MISMATCH;
                case INTERRUPT_ID_NOT_APPLICABLE ->
                        INTERRUPT_ID_NOT_APPLICABLE;
                case APPROVAL_RESUME_NOT_SUPPORTED ->
                        APPROVAL_RESUME_NOT_SUPPORTED;
                case TERMINAL_STATUS -> TERMINAL_STATUS;
                case NONE -> throw new IllegalArgumentException(
                        "a rejected response requires a rejection reason"
                );
            };
        }
    }

    enum RecoveryOutcome {
        TERMINAL_STEP_REPAIRED,
        NOT_RUNNING;

        static RecoveryOutcome from(
                AgentSnapshotRecoveryResult.Outcome outcome
        ) {
            AgentSnapshotRecoveryResult.Outcome actual =
                    Objects.requireNonNull(
                            outcome,
                            "recoveryOutcome cannot be null"
                    );
            return switch (actual) {
                case TERMINAL_STEP_REPAIRED -> TERMINAL_STEP_REPAIRED;
                case NOT_RUNNING -> NOT_RUNNING;
                case READY_TO_CONTINUE -> throw new IllegalArgumentException(
                        "a recovered response cannot be ready to continue"
                );
            };
        }
    }

    record Executed(
            Outcome outcome,
            AgentTaskView task,
            AgentRunResult runResult
    ) implements AgentResumeResponse {

        public Executed(AgentTaskView task, AgentRunResult runResult) {
            this(Outcome.EXECUTED, task, runResult);
        }

        public Executed {
            requireOutcome(outcome, Outcome.EXECUTED);
            Objects.requireNonNull(task, "task cannot be null");
            Objects.requireNonNull(runResult, "runResult cannot be null");
        }
    }

    record Rejected(
            Outcome outcome,
            AgentTaskView task,
            RejectionReason rejectionReason
    ) implements AgentResumeResponse {

        public Rejected(
                AgentTaskView task,
                RejectionReason rejectionReason
        ) {
            this(Outcome.REJECTED, task, rejectionReason);
        }

        public Rejected {
            requireOutcome(outcome, Outcome.REJECTED);
            Objects.requireNonNull(task, "task cannot be null");
            Objects.requireNonNull(
                    rejectionReason,
                    "rejectionReason cannot be null"
            );
        }
    }

    record Recovered(
            Outcome outcome,
            AgentTaskView task,
            RecoveryOutcome recoveryOutcome
    ) implements AgentResumeResponse {

        public Recovered(
                AgentTaskView task,
                RecoveryOutcome recoveryOutcome
        ) {
            this(Outcome.RECOVERED, task, recoveryOutcome);
        }

        public Recovered {
            requireOutcome(outcome, Outcome.RECOVERED);
            Objects.requireNonNull(task, "task cannot be null");
            Objects.requireNonNull(
                    recoveryOutcome,
                    "recoveryOutcome cannot be null"
            );
        }
    }

    private static void requireOutcome(
            Outcome actual,
            Outcome expected
    ) {
        Objects.requireNonNull(actual, "outcome cannot be null");
        if (actual != expected) {
            throw new IllegalArgumentException(
                    "outcome must be " + expected
            );
        }
    }
}
