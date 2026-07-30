package com.koawa.agent.agent.checkpoint.lease;

import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentExecutionConflictException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import com.koawa.agent.agent.exception.CheckpointNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAgentExecutionLeaseStoreTest {

    private static final Instant START =
            Instant.parse("2026-07-30T08:00:00Z");
    private static final Duration LEASE_DURATION =
            Duration.ofSeconds(30);

    private final InMemoryAgentCheckpointStore checkpoints =
            new InMemoryAgentCheckpointStore();
    private final MutableClock clock = new MutableClock(START);
    private final AtomicInteger ownerSequence = new AtomicInteger();
    private final InMemoryAgentExecutionLeaseStore leases =
            new InMemoryAgentExecutionLeaseStore(
                    checkpoints,
                    clock,
                    () -> "owner-" + ownerSequence.incrementAndGet()
            );

    @Test
    void shouldAcquireRenewAndReleaseWithoutChangingCheckpointRevision() {
        saveCheckpoint("task-1");

        AgentExecutionPermit acquired = leases.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );

        assertEquals("task-1", acquired.taskId());
        assertEquals("owner-1", acquired.ownerId());
        assertEquals(1, acquired.fencingToken());
        assertEquals(START.plusSeconds(30), acquired.expiresAt());
        assertFalse(acquired.toString().contains(acquired.ownerId()));

        clock.advance(Duration.ofSeconds(10));
        AgentExecutionPermit renewed = leases.renew(
                acquired,
                LEASE_DURATION
        );

        assertEquals(acquired.taskId(), renewed.taskId());
        assertEquals(acquired.ownerId(), renewed.ownerId());
        assertEquals(acquired.fencingToken(), renewed.fencingToken());
        assertEquals(START.plusSeconds(40), renewed.expiresAt());

        leases.release(renewed);

        assertEquals(
                clock.instant(),
                leases.load("task-1").orElseThrow().expiresAt()
        );
        assertEquals(
                0,
                checkpoints.load("task-1").orElseThrow().revision()
        );
    }

    @Test
    void shouldRejectSecondAcquireWhileLeaseIsActive() {
        saveCheckpoint("task-1");
        AgentExecutionPermit acquired = leases.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );

        AgentExecutionConflictException conflict = assertThrows(
                AgentExecutionConflictException.class,
                () -> leases.acquire(
                        "task-1",
                        0,
                        LEASE_DURATION
                )
        );

        assertEquals("task-1", conflict.getTaskId());
        assertEquals(acquired.expiresAt(), conflict.getRetryAt());
        assertEquals(1, ownerSequence.get());
    }

    @Test
    void shouldFenceExpiredOwnerAfterTakeover() {
        saveCheckpoint("task-1");
        AgentExecutionPermit expired = leases.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );
        clock.advance(Duration.ofSeconds(31));

        AgentExecutionLeaseLostException expiry = assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> leases.renew(expired, LEASE_DURATION)
        );
        assertEquals(Reason.LEASE_EXPIRED, expiry.getReason());

        AgentExecutionPermit replacement = leases.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );

        assertEquals(2, replacement.fencingToken());
        assertNotEquals(expired.ownerId(), replacement.ownerId());
        AgentExecutionLeaseLostException renewFailure = assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> leases.renew(expired, LEASE_DURATION)
        );
        AgentExecutionLeaseLostException releaseFailure = assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> leases.release(expired)
        );
        assertEquals(
                Reason.OWNER_OR_TOKEN_MISMATCH,
                renewFailure.getReason()
        );
        assertEquals(
                Reason.OWNER_OR_TOKEN_MISMATCH,
                releaseFailure.getReason()
        );
    }

    @Test
    void shouldRetainTokenHistoryAfterRelease() {
        saveCheckpoint("task-1");
        AgentExecutionPermit first = leases.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );
        clock.advance(Duration.ofSeconds(5));
        leases.release(first);

        AgentExecutionPermit second = leases.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );

        assertEquals(2, second.fencingToken());
        assertNotEquals(first.ownerId(), second.ownerId());
    }

    @Test
    void shouldRequireExistingCheckpointAtExpectedRevision() {
        assertThrows(
                CheckpointNotFoundException.class,
                () -> leases.acquire(
                        "missing-task",
                        0,
                        LEASE_DURATION
                )
        );
        saveCheckpoint("task-1");

        CheckpointConflictException conflict = assertThrows(
                CheckpointConflictException.class,
                () -> leases.acquire(
                        "task-1",
                        1,
                        LEASE_DURATION
                )
        );

        assertEquals(1, conflict.getExpectedRevision());
        assertEquals(0L, conflict.getActualRevision());
        assertEquals(0, ownerSequence.get());
    }

    @Test
    void shouldAllowOnlyOneConcurrentAcquire() throws Exception {
        saveCheckpoint("task-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Boolean> acquire = () -> {
            ready.countDown();
            start.await();
            try {
                leases.acquire("task-1", 0, LEASE_DURATION);
                return true;
            } catch (AgentExecutionConflictException ignored) {
                return false;
            }
        };

        try {
            Future<Boolean> first = executor.submit(acquire);
            Future<Boolean> second = executor.submit(acquire);
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            long acquiredCount = List.of(
                            first.get(2, TimeUnit.SECONDS),
                            second.get(2, TimeUnit.SECONDS)
                    )
                    .stream()
                    .filter(Boolean::booleanValue)
                    .count();

            assertEquals(1, acquiredCount);
            assertEquals(
                    1,
                    leases.load("task-1")
                            .orElseThrow()
                            .fencingToken()
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private void saveCheckpoint(String taskId) {
        checkpoints.save(
                new AgentTaskSnapshot(
                        AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                        taskId,
                        "conversation-1",
                        "user-1",
                        0,
                        AgentTaskStatus.RUNNING,
                        "question",
                        0,
                        4,
                        START.plusSeconds(300),
                        List.of(),
                        List.of(),
                        Map.of(),
                        null,
                        START,
                        START
                ),
                AgentCheckpointStore.NO_REVISION
        );
    }

    private static final class MutableClock extends Clock {

        private volatile Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
