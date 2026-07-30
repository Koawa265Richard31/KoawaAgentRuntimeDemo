package com.koawa.agent.agent.checkpoint.lease;

import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException;
import com.koawa.agent.agent.exception.AgentExecutionLeaseLostException.Reason;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns one acquired execution permit and renews it until the run closes.
 */
@Slf4j
public final class AgentExecutionLeaseSession implements AutoCloseable {

    private final Object monitor = new Object();
    private final AgentExecutionLeaseStore leaseStore;
    private final Duration leaseDuration;
    private final ScheduledExecutorService scheduler;

    private AgentExecutionPermit permit;
    private AgentExecutionLeaseLostException failure;
    private boolean closed;

    private AgentExecutionLeaseSession(
            AgentExecutionLeaseStore leaseStore,
            AgentExecutionPermit permit,
            Duration leaseDuration,
            Duration renewInterval
    ) {
        this.leaseStore = Objects.requireNonNull(
                leaseStore,
                "leaseStore cannot be null"
        );
        this.permit = Objects.requireNonNull(
                permit,
                "permit cannot be null"
        );
        this.leaseDuration = requireDuration(
                leaseDuration,
                "leaseDuration"
        );
        Duration actualRenewInterval = requireDuration(
                renewInterval,
                "renewInterval"
        );
        if (actualRenewInterval.compareTo(this.leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "renewInterval must be shorter than leaseDuration"
            );
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "agent-execution-lease-heartbeat"
                    );
                    thread.setDaemon(true);
                    return thread;
                }
        );
        long intervalMillis = requireMillis(
                actualRenewInterval,
                "renewInterval"
        );
        scheduler.scheduleWithFixedDelay(
                this::renewSafely,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    public static AgentExecutionLeaseSession start(
            AgentExecutionLeaseStore leaseStore,
            AgentExecutionPermit permit,
            Duration leaseDuration,
            Duration renewInterval
    ) {
        return new AgentExecutionLeaseSession(
                leaseStore,
                permit,
                leaseDuration,
                renewInterval
        );
    }

    /**
     * Returns the latest renewed permit or throws the recorded heartbeat
     * failure.
     */
    public AgentExecutionPermit currentPermit() {
        synchronized (monitor) {
            requireActive();
            return permit;
        }
    }

    /**
     * Stops execution at a safe boundary after any renewal failure.
     */
    public void requireActive() {
        synchronized (monitor) {
            if (failure != null) {
                throw failure;
            }
            if (closed) {
                throw new IllegalStateException(
                        "execution lease session is closed"
                );
            }
        }
    }

    @Override
    public void close() {
        AgentExecutionPermit permitToRelease;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            permitToRelease = permit;
        }

        scheduler.shutdownNow();
        try {
            leaseStore.release(permitToRelease);
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to release execution lease for task {} "
                            + "with fencing token {}: {}",
                    permitToRelease.taskId(),
                    permitToRelease.fencingToken(),
                    exception.getMessage()
            );
            log.debug("Execution lease release failure", exception);
        }
    }

    private void renewSafely() {
        synchronized (monitor) {
            if (closed || failure != null) {
                return;
            }
            try {
                permit = leaseStore.renew(permit, leaseDuration);
            } catch (RuntimeException exception) {
                failure = toLeaseLost(exception);
            }
        }
    }

    private AgentExecutionLeaseLostException toLeaseLost(
            RuntimeException exception
    ) {
        if (exception
                instanceof AgentExecutionLeaseLostException leaseLost) {
            return leaseLost;
        }
        return new AgentExecutionLeaseLostException(
                permit.taskId(),
                permit.fencingToken(),
                Reason.RENEWAL_FAILED,
                exception
        );
    }

    private static Duration requireDuration(
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
        requireMillis(actual, fieldName);
        return actual;
    }

    private static long requireMillis(
            Duration duration,
            String fieldName
    ) {
        long millis = duration.toMillis();
        if (millis < 1) {
            throw new IllegalArgumentException(
                    fieldName + " must be at least one millisecond"
            );
        }
        return millis;
    }
}
