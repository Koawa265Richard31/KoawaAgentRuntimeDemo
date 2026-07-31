package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.lease.InMemoryAgentExecutionLeaseStore;
import com.koawa.agent.agent.checkpoint.resume.AgentInterruptConsumptionService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeClaimService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeCommand;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeExecutionResult;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeExecutionService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeService;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentFencedCheckpointWriter;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentFencedCheckpointWriter;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.event.AgentEventSink;
import com.koawa.agent.agent.executor.AgentActionExecutor;
import com.koawa.agent.agent.planner.AgentPlanner;
import com.koawa.agent.agent.recovery.AgentRecoveryDecision;
import com.koawa.agent.agent.runner.AgentCancellationChecker;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;
import com.koawa.agent.agent.runner.AgentLoopRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentResumeExecutionServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-31T10:00:00Z");
    private static final Duration LEASE_DURATION =
            Duration.ofSeconds(30);
    private static final Duration RENEW_INTERVAL =
            Duration.ofSeconds(10);

    private InMemoryAgentCheckpointStore checkpointStore;
    private InMemoryAgentExecutionLeaseStore leaseStore;
    private AgentResumeClaimService claimService;
    private AtomicInteger fencedWrites;

    @BeforeEach
    void setUp() {
        checkpointStore = new InMemoryAgentCheckpointStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentTaskSnapshotMapper mapper = new AgentTaskSnapshotMapper();
        AtomicInteger owners = new AtomicInteger();
        leaseStore = new InMemoryAgentExecutionLeaseStore(
                checkpointStore,
                clock,
                () -> "owner-" + owners.incrementAndGet()
        );
        InMemoryAgentFencedCheckpointWriter delegate =
                new InMemoryAgentFencedCheckpointWriter(
                        checkpointStore,
                        leaseStore,
                        clock
                );
        fencedWrites = new AtomicInteger();
        AgentFencedCheckpointWriter fencedWriter =
                (snapshot, expectedRevision, permit) -> {
                    fencedWrites.incrementAndGet();
                    return delegate.save(
                            snapshot,
                            expectedRevision,
                            permit
                    );
                };
        AgentCheckpointService checkpointService =
                new AgentCheckpointService(
                        checkpointStore,
                        mapper,
                        clock,
                        fencedWriter
                );
        claimService = new AgentResumeClaimService(
                new AgentResumeService(checkpointStore),
                new AgentInterruptConsumptionService(
                        checkpointStore,
                        mapper,
                        clock
                ),
                new AgentSnapshotRecoveryService(
                        checkpointStore,
                        mapper,
                        clock,
                        () -> "recovered-interrupt",
                        fencedWriter
                ),
                leaseStore,
                checkpointService,
                clock,
                LEASE_DURATION,
                RENEW_INTERVAL
        );
    }

    @Test
    void shouldRunFromRecoveredStepWithFencedLifecycleAndReleaseLease() {
        saveNew(snapshot(
                "executed-task",
                0,
                AgentTaskStatus.RUNNING,
                List.of(),
                Map.of(),
                null
        ));
        checkpointStore.save(
                snapshot(
                        "executed-task",
                        1,
                        AgentTaskStatus.RUNNING,
                        List.of(step(
                                0,
                                AgentActionType.CALL_MCP_TOOL,
                                "existing result"
                        )),
                        Map.of(),
                        null
                ),
                0
        );
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentLoopRunner runner = runner(
                state -> {
                    plannerCalls.incrementAndGet();
                    assertEquals(1, state.getCurrentStep());
                    assertEquals(1, state.getSteps().size());
                    return AgentAction.builder()
                            .type(AgentActionType.FINAL_ANSWER)
                            .thought("finish")
                            .build();
                },
                (action, state) -> AgentObservation.builder()
                        .actionType(action.getType())
                        .content("final answer")
                        .success(true)
                        .build()
        );

        AgentResumeExecutionResult.Executed executed =
                assertInstanceOf(
                        AgentResumeExecutionResult.Executed.class,
                        new AgentResumeExecutionService(
                                claimService,
                                runner
                        ).resume(new AgentResumeCommand(
                                "executed-task",
                                1,
                                null
                        ))
                );

        assertEquals(1, plannerCalls.get());
        assertEquals(
                AgentStopReason.FINAL_ANSWER,
                executed.runResult().stopReason()
        );
        assertEquals(2, executed.runResult().stepCount());
        AgentTaskSnapshot saved = checkpointStore
                .load("executed-task")
                .orElseThrow();
        assertEquals(3, saved.revision());
        assertEquals(AgentTaskStatus.COMPLETED, saved.status());
        assertEquals(2, saved.steps().size());
        assertEquals(2, fencedWrites.get());
        assertEquals(
                NOW,
                leaseStore.load("executed-task")
                        .orElseThrow()
                        .expiresAt()
        );
    }

    @Test
    void shouldReturnRejectedWithoutEnteringAgentLoop() {
        saveNew(snapshot(
                "rejected-task",
                0,
                AgentTaskStatus.COMPLETED,
                List.of(),
                Map.of(),
                null
        ));
        AgentLoopRunner runner = mock(AgentLoopRunner.class);

        AgentResumeExecutionResult result =
                new AgentResumeExecutionService(
                        claimService,
                        runner
                ).resume(new AgentResumeCommand(
                        "rejected-task",
                        0,
                        null
                ));

        assertInstanceOf(
                AgentResumeExecutionResult.Rejected.class,
                result
        );
        verifyNoInteractions(runner);
        assertTrue(leaseStore.load("rejected-task").isEmpty());
    }

    @Test
    void shouldReturnRecoveredWithoutEnteringAgentLoop() {
        saveNew(snapshot(
                "recovered-task",
                0,
                AgentTaskStatus.RUNNING,
                List.of(step(
                        0,
                        AgentActionType.FINAL_ANSWER,
                        "already finished"
                )),
                Map.of(),
                null
        ));
        AgentLoopRunner runner = mock(AgentLoopRunner.class);

        AgentResumeExecutionResult.Recovered recovered =
                assertInstanceOf(
                        AgentResumeExecutionResult.Recovered.class,
                        new AgentResumeExecutionService(
                                claimService,
                                runner
                        ).resume(new AgentResumeCommand(
                                "recovered-task",
                                0,
                                null
                        ))
                );

        assertEquals(
                AgentTaskStatus.COMPLETED,
                recovered.recovery().snapshot().status()
        );
        verifyNoInteractions(runner);
        assertEquals(1, fencedWrites.get());
        assertEquals(
                NOW,
                leaseStore.load("recovered-task")
                        .orElseThrow()
                        .expiresAt()
        );
    }

    @Test
    void shouldReleaseLeaseWhenRunnerFails() {
        saveNew(snapshot(
                "failed-runner-task",
                0,
                AgentTaskStatus.RUNNING,
                List.of(),
                Map.of(),
                null
        ));
        AgentLoopRunner runner = mock(AgentLoopRunner.class);
        when(runner.run(
                any(AgentState.class),
                any(AgentCheckpointLifecycle.class)
        )).thenThrow(new IllegalStateException("runner failed"));
        AgentResumeExecutionService service =
                new AgentResumeExecutionService(
                        claimService,
                        runner
                );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> service.resume(new AgentResumeCommand(
                        "failed-runner-task",
                        0,
                        null
                ))
        );

        assertEquals("runner failed", failure.getMessage());
        assertEquals(
                NOW,
                leaseStore.load("failed-runner-task")
                        .orElseThrow()
                        .expiresAt()
        );
        assertEquals(0, fencedWrites.get());
    }

    private AgentLoopRunner runner(
            AgentPlanner planner,
            AgentActionExecutor executor
    ) {
        return new AgentLoopRunner(
                planner,
                executor,
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                failureType -> AgentRecoveryDecision.STOP,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private void saveNew(AgentTaskSnapshot snapshot) {
        checkpointStore.save(
                snapshot,
                AgentCheckpointStore.NO_REVISION
        );
    }

    private AgentTaskSnapshot snapshot(
            String taskId,
            long revision,
            AgentTaskStatus status,
            List<StepSnapshot> steps,
            Map<String, String> recoveryContext,
            PendingInterrupt interrupt
    ) {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                taskId,
                "conversation-1",
                "user-1",
                revision,
                status,
                "resume task",
                steps.size(),
                4,
                NOW.plusSeconds(300),
                steps,
                List.of(),
                recoveryContext,
                interrupt,
                NOW,
                NOW
        );
    }

    private StepSnapshot step(
            int index,
            AgentActionType actionType,
            String observationContent
    ) {
        return new StepSnapshot(
                index,
                actionType,
                "step " + index,
                "{}",
                observationContent,
                "{}",
                true,
                null
        );
    }
}
