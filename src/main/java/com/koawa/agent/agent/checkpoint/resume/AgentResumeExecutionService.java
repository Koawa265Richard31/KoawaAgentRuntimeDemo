package com.koawa.agent.agent.checkpoint.resume;

import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;
import com.koawa.agent.agent.runner.AgentLoopRunner;

import java.util.Objects;

/**
 * Runs only successfully claimed Resume requests and owns execution cleanup.
 */
public final class AgentResumeExecutionService {

    private final AgentResumeClaimService claimService;
    private final AgentLoopRunner runner;

    public AgentResumeExecutionService(
            AgentResumeClaimService claimService,
            AgentLoopRunner runner
    ) {
        this.claimService = Objects.requireNonNull(
                claimService,
                "claimService cannot be null"
        );
        this.runner = Objects.requireNonNull(
                runner,
                "runner cannot be null"
        );
    }

    public AgentResumeExecutionResult resume(
            AgentResumeCommand command
    ) {
        Objects.requireNonNull(command, "command cannot be null");

        AgentResumeClaimResult claimResult =
                claimService.claim(command);
        if (claimResult
                instanceof AgentResumeClaimResult.Rejected rejected) {
            return new AgentResumeExecutionResult.Rejected(
                    rejected.decision()
            );
        }
        if (claimResult
                instanceof AgentResumeClaimResult.Recovered recovered) {
            return new AgentResumeExecutionResult.Recovered(
                    recovered.recovery()
            );
        }

        AgentClaimedExecution execution =
                ((AgentResumeClaimResult.Claimed) claimResult)
                        .execution();
        try (execution) {
            execution.requireActive();
            AgentCheckpointLifecycle lifecycle =
                    execution.checkpointLifecycle();
            AgentState terminalState = Objects.requireNonNull(
                    runner.run(execution.state(), lifecycle),
                    "runner returned null state"
            );
            lifecycle.completed(terminalState);
            return new AgentResumeExecutionResult.Executed(
                    AgentRunResult.from(terminalState)
            );
        }
    }
}
