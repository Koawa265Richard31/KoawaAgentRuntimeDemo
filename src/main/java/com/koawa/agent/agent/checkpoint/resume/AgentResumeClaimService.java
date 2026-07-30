package com.koawa.agent.agent.checkpoint.resume;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionLeaseSession;
import com.koawa.agent.agent.checkpoint.lease.AgentExecutionLeaseStore;
import com.koawa.agent.agent.checkpoint.lease.AgentExecutionPermit;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointService;
import com.koawa.agent.agent.checkpoint.snapshot.PersistentAgentCheckpointLifecycle;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Coordinates Resume validation, interrupt consumption, execution claim and
 * snapshot recovery without running the Agent loop.
 */
@Slf4j
public final class AgentResumeClaimService {

    private final AgentResumeService resumeService;
    private final AgentInterruptConsumptionService consumptionService;
    private final AgentSnapshotRecoveryService recoveryService;
    private final AgentExecutionLeaseStore leaseStore;
    private final AgentCheckpointService checkpointService;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration renewInterval;

    public AgentResumeClaimService(
            AgentResumeService resumeService,
            AgentInterruptConsumptionService consumptionService,
            AgentSnapshotRecoveryService recoveryService,
            AgentExecutionLeaseStore leaseStore,
            AgentCheckpointService checkpointService,
            Clock clock,
            Duration leaseDuration,
            Duration renewInterval
    ) {
        this.resumeService = Objects.requireNonNull(
                resumeService,
                "resumeService cannot be null"
        );
        this.consumptionService = Objects.requireNonNull(
                consumptionService,
                "consumptionService cannot be null"
        );
        this.recoveryService = Objects.requireNonNull(
                recoveryService,
                "recoveryService cannot be null"
        );
        this.leaseStore = Objects.requireNonNull(
                leaseStore,
                "leaseStore cannot be null"
        );
        this.checkpointService = Objects.requireNonNull(
                checkpointService,
                "checkpointService cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.leaseDuration = requireDuration(
                leaseDuration,
                "leaseDuration"
        );
        this.renewInterval = requireDuration(
                renewInterval,
                "renewInterval"
        );
        if (this.renewInterval.compareTo(this.leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "renewInterval must be shorter than leaseDuration"
            );
        }
    }

    public AgentResumeClaimResult claim(AgentResumeCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        AgentResumeResult decision = resumeService.evaluate(command);
        if (!decision.accepted()) {
            return new AgentResumeClaimResult.Rejected(decision);
        }

        long claimRevision = switch (decision.nextAction()) {
            case ACQUIRE_EXECUTION_CLAIM -> decision.revision();
            case CONSUME_USER_INPUT_INTERRUPT ->
                    consumptionService.consume(command)
                            .snapshot()
                            .revision();
            case REJECT -> throw new IllegalStateException(
                    "accepted Resume decision cannot reject"
            );
        };
        return claimRunning(command.taskId(), claimRevision);
    }

    private AgentResumeClaimResult claimRunning(
            String taskId,
            long expectedRevision
    ) {
        AgentExecutionPermit permit = leaseStore.acquire(
                taskId,
                expectedRevision,
                leaseDuration
        );
        AgentExecutionLeaseSession session = null;
        try {
            AgentSnapshotRecoveryResult recovery =
                    recoveryService.restore(
                            taskId,
                            expectedRevision,
                            permit
                    );
            if (!recovery.shouldContinue()) {
                releaseQuietly(permit);
                return new AgentResumeClaimResult.Recovered(recovery);
            }

            session = AgentExecutionLeaseSession.start(
                    leaseStore,
                    permit,
                    leaseDuration,
                    renewInterval
            );
            PersistentAgentCheckpointLifecycle lifecycle =
                    new PersistentAgentCheckpointLifecycle(
                            checkpointService,
                            clock,
                            session
                    );
            AgentClaimedExecution execution =
                    new AgentClaimedExecution(
                            recovery.snapshot(),
                            recovery.state(),
                            lifecycle,
                            session
                    );
            return new AgentResumeClaimResult.Claimed(execution);
        } catch (RuntimeException | Error failure) {
            if (session == null) {
                releaseQuietly(permit);
            } else {
                session.close();
            }
            throw failure;
        }
    }

    private void releaseQuietly(AgentExecutionPermit permit) {
        try {
            leaseStore.release(permit);
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to release Resume claim for task {} "
                            + "with fencing token {}: {}",
                    permit.taskId(),
                    permit.fencingToken(),
                    exception.getMessage()
            );
            log.debug("Resume claim release failure", exception);
        }
    }

    private Duration requireDuration(
            Duration duration,
            String fieldName
    ) {
        Duration actual = Objects.requireNonNull(
                duration,
                fieldName + " cannot be null"
        );
        if (actual.isZero() || actual.isNegative()) {
            throw new IllegalArgumentException(
                    fieldName + " must be positive"
            );
        }
        if (actual.toMillis() < 1) {
            throw new IllegalArgumentException(
                    fieldName + " must be at least one millisecond"
            );
        }
        return actual;
    }
}
