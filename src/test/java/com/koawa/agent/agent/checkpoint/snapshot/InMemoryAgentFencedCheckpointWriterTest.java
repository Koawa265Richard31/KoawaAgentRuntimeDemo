package com.koawa.agent.agent.checkpoint.snapshot;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionPermit;
import com.koawa.agent.agent.checkpoint.lease.InMemoryAgentExecutionLeaseStore;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;
import com.koawa.agent.agent.exception.CheckpointConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryAgentFencedCheckpointWriterTest {

    private static final Instant NOW =
            Instant.parse("2026-07-30T10:00:00Z");
    private static final Duration LEASE_DURATION =
            Duration.ofSeconds(30);

    private MutableClock clock;
    private InMemoryAgentCheckpointStore checkpointStore;
    private InMemoryAgentExecutionLeaseStore leaseStore;
    private InMemoryAgentFencedCheckpointWriter writer;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        checkpointStore = new InMemoryAgentCheckpointStore();
        leaseStore = new InMemoryAgentExecutionLeaseStore(
                checkpointStore,
                clock,
                () -> "owner-1"
        );
        writer = new InMemoryAgentFencedCheckpointWriter(
                checkpointStore,
                leaseStore,
                clock
        );
        checkpointStore.save(
                snapshot(0, NOW),
                AgentCheckpointStore.NO_REVISION
        );
    }

    @Test
    void shouldSaveWhenRevisionAndPermitAreCurrent() {
        AgentExecutionPermit permit = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );

        AgentTaskSnapshot saved = writer.save(
                snapshot(1, NOW.plusSeconds(1)),
                0,
                permit
        );

        assertEquals(1, saved.revision());
        assertEquals(
                1,
                checkpointStore.load("task-1")
                        .orElseThrow()
                        .revision()
        );
    }

    @Test
    void shouldKeepRevisionConflictForCurrentPermit() {
        AgentExecutionPermit permit = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );

        assertThrows(
                CheckpointConflictException.class,
                () -> writer.save(
                        snapshot(2, NOW.plusSeconds(1)),
                        1,
                        permit
                )
        );
    }

    @Test
    void shouldRejectExpiredPermitWithoutWriting() {
        AgentExecutionPermit permit = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );
        clock.advance(Duration.ofSeconds(31));

        AgentExecutionLeaseLostException failure = assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> writer.save(
                        snapshot(1, NOW.plusSeconds(1)),
                        0,
                        permit
                )
        );

        assertEquals(Reason.LEASE_EXPIRED, failure.getReason());
        assertEquals(
                0,
                checkpointStore.load("task-1")
                        .orElseThrow()
                        .revision()
        );
    }

    @Test
    void shouldPreferLeaseLostWhenTokenAndRevisionAreBothStale() {
        AgentExecutionPermit first = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );
        clock.advance(Duration.ofSeconds(31));
        AgentExecutionPermit second = leaseStore.acquire(
                "task-1",
                0,
                LEASE_DURATION
        );
        writer.save(
                snapshot(1, NOW.plusSeconds(1)),
                0,
                second
        );

        AgentExecutionLeaseLostException failure = assertThrows(
                AgentExecutionLeaseLostException.class,
                () -> writer.save(
                        snapshot(1, NOW.plusSeconds(1)),
                        0,
                        first
                )
        );

        assertEquals(
                Reason.OWNER_OR_TOKEN_MISMATCH,
                failure.getReason()
        );
    }

    private AgentTaskSnapshot snapshot(
            long revision,
            Instant updatedAt
    ) {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                "task-1",
                "conversation-1",
                "user-1",
                revision,
                AgentTaskStatus.RUNNING,
                "question",
                0,
                4,
                NOW.plusSeconds(300),
                List.of(),
                List.of(),
                Map.of(),
                null,
                NOW,
                updatedAt
        );
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
