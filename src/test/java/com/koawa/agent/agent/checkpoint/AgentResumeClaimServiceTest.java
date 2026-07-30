package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.lease.InMemoryAgentExecutionLeaseStore;
import com.koawa.agent.agent.checkpoint.resume.AgentClaimedExecution;
import com.koawa.agent.agent.checkpoint.resume.AgentInterruptConsumptionService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeClaimResult;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeClaimService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeCommand;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeResult;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeService;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentFencedCheckpointWriter;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentFencedCheckpointWriter;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentExecutionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResumeClaimServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-31T08:00:00Z");
    private static final Duration LEASE_DURATION =
            Duration.ofSeconds(30);
    private static final Duration RENEW_INTERVAL =
            Duration.ofSeconds(10);

    private InMemoryAgentCheckpointStore checkpointStore;
    private InMemoryAgentExecutionLeaseStore leaseStore;
    private AgentTaskSnapshotMapper mapper;
    private Clock clock;
    private AgentCheckpointService checkpointService;
    private AgentResumeClaimService claimService;
    private AtomicInteger fencedWrites;

    @BeforeEach
    void setUp() {
        checkpointStore = new InMemoryAgentCheckpointStore();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mapper = new AgentTaskSnapshotMapper();
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
        checkpointService =
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
    void shouldReturnOwnedExecutionForRunningTaskAndReleaseOnClose() {
        save(snapshot(
                "running-task",
                AgentTaskStatus.RUNNING,
                List.of(),
                Map.of(),
                null
        ));

        AgentResumeClaimResult result = claimService.claim(
                new AgentResumeCommand("running-task", 0, null)
        );
        AgentClaimedExecution execution = assertInstanceOf(
                AgentResumeClaimResult.Claimed.class,
                result
        ).execution();

        assertEquals("running-task", execution.state().getTaskId());
        assertEquals(0, execution.state().getCurrentStep());
        assertEquals(0, execution.snapshot().revision());
        assertNotNull(execution.checkpointLifecycle());
        execution.requireActive();
        assertTrue(leaseStore.load("running-task")
                .orElseThrow()
                .expiresAt()
                .isAfter(NOW));

        execution.close();

        assertEquals(
                NOW,
                leaseStore.load("running-task")
                        .orElseThrow()
                        .expiresAt()
        );
        assertEquals(0, fencedWrites.get());
    }

    @Test
    void shouldAllowOnlyOneConcurrentResumeIntoExecution() throws Exception {
        save(snapshot(
                "concurrent-task",
                AgentTaskStatus.RUNNING,
                List.of(),
                Map.of(),
                null
        ));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> attempt = () -> {
            ready.countDown();
            start.await();
            try {
                return claimService.claim(new AgentResumeCommand(
                        "concurrent-task",
                        0,
                        null
                ));
            } catch (RuntimeException exception) {
                return exception;
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AgentClaimedExecution claimedExecution = null;
        try {
            Future<Object> first = executor.submit(attempt);
            Future<Object> second = executor.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Object> outcomes = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS)
            );
            List<AgentResumeClaimResult.Claimed> claimed =
                    outcomes.stream()
                            .filter(AgentResumeClaimResult.Claimed.class
                                    ::isInstance)
                            .map(AgentResumeClaimResult.Claimed.class::cast)
                            .toList();
            List<AgentExecutionConflictException> conflicts =
                    outcomes.stream()
                            .filter(AgentExecutionConflictException.class
                                    ::isInstance)
                            .map(AgentExecutionConflictException.class::cast)
                            .toList();

            assertEquals(1, claimed.size());
            assertEquals(1, conflicts.size());
            assertEquals(
                    "concurrent-task",
                    conflicts.get(0).getTaskId()
            );
            claimedExecution = claimed.get(0).execution();
            claimedExecution.requireActive();
        } finally {
            if (claimedExecution != null) {
                claimedExecution.close();
            }
            executor.shutdownNow();
        }
    }

    @Test
    void shouldConsumeWaitingInputBeforeClaimingNewRevision() {
        save(snapshot(
                "input-task",
                AgentTaskStatus.WAITING_FOR_INPUT,
                List.of(step(
                        0,
                        AgentActionType.ASK_CLARIFICATION,
                        "Which repository?"
                )),
                Map.of(
                        "stopReason",
                        AgentStopReason.ASK_CLARIFICATION.name(),
                        "finalAnswer",
                        "Which repository?"
                ),
                new PendingInterrupt(
                        "interrupt-1",
                        InterruptType.USER_INPUT,
                        "Which repository?",
                        Map.of(),
                        NOW
                )
        ));

        AgentResumeClaimResult result = claimService.claim(
                new AgentResumeCommand(
                        "input-task",
                        0,
                        "interrupt-1",
                        "repository-a"
                )
        );
        AgentClaimedExecution execution = assertInstanceOf(
                AgentResumeClaimResult.Claimed.class,
                result
        ).execution();
        try {
            assertEquals(1, execution.snapshot().revision());
            assertEquals(
                    AgentTaskStatus.RUNNING,
                    execution.snapshot().status()
            );
            assertEquals(
                    0,
                    execution.state().getConsumedUserInputStep()
            );
            assertEquals(
                    "repository-a",
                    execution.state()
                            .getHistorySnapshot()
                            .get(0)
                            .getContent()
            );
            assertEquals(
                    1,
                    checkpointStore.load("input-task")
                            .orElseThrow()
                            .revision()
            );
        } finally {
            execution.close();
        }
    }

    @Test
    void shouldRejectIneligibleTaskWithoutAcquiringLease() {
        save(snapshot(
                "completed-task",
                AgentTaskStatus.COMPLETED,
                List.of(),
                Map.of(),
                null
        ));

        AgentResumeClaimResult.Rejected rejected = assertInstanceOf(
                AgentResumeClaimResult.Rejected.class,
                claimService.claim(new AgentResumeCommand(
                        "completed-task",
                        0,
                        null
                ))
        );

        assertEquals(
                AgentResumeResult.RejectionReason.TERMINAL_STATUS,
                rejected.decision().rejectionReason()
        );
        assertTrue(leaseStore.load("completed-task").isEmpty());
    }

    @Test
    void shouldFenceTerminalRepairAndNotEnterExecution() {
        save(snapshot(
                "terminal-boundary-task",
                AgentTaskStatus.RUNNING,
                List.of(step(
                        0,
                        AgentActionType.FINAL_ANSWER,
                        "done"
                )),
                Map.of(),
                null
        ));

        AgentResumeClaimResult.Recovered recovered = assertInstanceOf(
                AgentResumeClaimResult.Recovered.class,
                claimService.claim(new AgentResumeCommand(
                        "terminal-boundary-task",
                        0,
                        null
                ))
        );

        assertEquals(
                AgentTaskStatus.COMPLETED,
                recovered.recovery().snapshot().status()
        );
        assertEquals(1, recovered.recovery().snapshot().revision());
        assertEquals(1, fencedWrites.get());
        assertEquals(
                NOW,
                leaseStore.load("terminal-boundary-task")
                        .orElseThrow()
                        .expiresAt()
        );
    }

    @Test
    void shouldReleasePermitWhenRecoveryFailsAfterAcquire() {
        save(snapshot(
                "failed-recovery-task",
                AgentTaskStatus.RUNNING,
                List.of(step(
                        0,
                        AgentActionType.FINAL_ANSWER,
                        "done"
                )),
                Map.of(),
                null
        ));
        AgentResumeClaimService failingService =
                new AgentResumeClaimService(
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
                                () -> "unused-interrupt"
                        ),
                        leaseStore,
                        checkpointService,
                        clock,
                        LEASE_DURATION,
                        RENEW_INTERVAL
                );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> failingService.claim(new AgentResumeCommand(
                        "failed-recovery-task",
                        0,
                        null
                ))
        );

        assertEquals(
                "fenced checkpoint writer is not configured",
                failure.getMessage()
        );
        assertEquals(
                NOW,
                leaseStore.load("failed-recovery-task")
                        .orElseThrow()
                        .expiresAt()
        );
        assertEquals(0, fencedWrites.get());
    }

    private void save(AgentTaskSnapshot snapshot) {
        checkpointStore.save(
                snapshot,
                AgentCheckpointStore.NO_REVISION
        );
    }

    private AgentTaskSnapshot snapshot(
            String taskId,
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
                0,
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
