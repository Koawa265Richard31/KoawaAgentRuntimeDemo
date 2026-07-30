package com.koawa.agent.agent.checkpoint.lease;

import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionLeaseSessionTest {

    private static final Duration LEASE_DURATION =
            Duration.ofSeconds(1);
    private static final Duration RENEW_INTERVAL =
            Duration.ofMillis(10);

    @Test
    void shouldRenewInBackgroundAndReleaseLatestPermit() throws Exception {
        RecordingLeaseStore store = new RecordingLeaseStore(false);
        AgentExecutionPermit initial = permit(1);

        try (AgentExecutionLeaseSession session =
                     AgentExecutionLeaseSession.start(
                             store,
                             initial,
                             LEASE_DURATION,
                             RENEW_INTERVAL
                     )) {
            assertTrue(store.renewed.await(2, TimeUnit.SECONDS));
            assertTrue(
                    session.currentPermit().expiresAt()
                            .isAfter(initial.expiresAt())
            );
        }

        assertEquals(1, store.releaseCount.get());
        assertTrue(
                store.released.expiresAt()
                        .isAfter(initial.expiresAt())
        );
    }

    @Test
    void shouldSurfaceUnexpectedRenewalFailureAsLeaseLost()
            throws Exception {
        RecordingLeaseStore store = new RecordingLeaseStore(true);

        try (AgentExecutionLeaseSession session =
                     AgentExecutionLeaseSession.start(
                             store,
                             permit(1),
                             LEASE_DURATION,
                             RENEW_INTERVAL
            )) {
            assertTrue(store.renewed.await(2, TimeUnit.SECONDS));

            AgentExecutionLeaseLostException failure =
                    awaitLeaseLost(session);

            assertEquals(Reason.RENEWAL_FAILED, failure.getReason());
            assertEquals(
                    IllegalStateException.class,
                    failure.getCause().getClass()
            );
        }
    }

    @Test
    void shouldRejectInvalidHeartbeatIntervals() {
        RecordingLeaseStore store = new RecordingLeaseStore(false);

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentExecutionLeaseSession.start(
                        store,
                        permit(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)
                )
        );
    }

    private AgentExecutionLeaseLostException awaitLeaseLost(
            AgentExecutionLeaseSession session
    ) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                session.requireActive();
            } catch (AgentExecutionLeaseLostException failure) {
                return failure;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(
                "lease session did not surface renewal failure"
        );
    }

    private AgentExecutionPermit permit(long token) {
        return new AgentExecutionPermit(
                "task-1",
                "owner-1",
                token,
                Instant.parse("2026-07-30T10:00:30Z")
        );
    }

    private static final class RecordingLeaseStore
            implements AgentExecutionLeaseStore {

        private final boolean failRenew;
        private final CountDownLatch renewed = new CountDownLatch(1);
        private final AtomicInteger releaseCount = new AtomicInteger();
        private volatile AgentExecutionPermit released;

        private RecordingLeaseStore(boolean failRenew) {
            this.failRenew = failRenew;
        }

        @Override
        public AgentExecutionPermit acquire(
                String taskId,
                long expectedRevision,
                Duration leaseDuration
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentExecutionPermit renew(
                AgentExecutionPermit permit,
                Duration leaseDuration
        ) {
            renewed.countDown();
            if (failRenew) {
                throw new IllegalStateException("database unavailable");
            }
            return new AgentExecutionPermit(
                    permit.taskId(),
                    permit.ownerId(),
                    permit.fencingToken(),
                    permit.expiresAt().plus(leaseDuration)
            );
        }

        @Override
        public void release(AgentExecutionPermit permit) {
            released = permit;
            releaseCount.incrementAndGet();
        }

        @Override
        public Optional<AgentExecutionPermit> load(String taskId) {
            return Optional.empty();
        }
    }
}
