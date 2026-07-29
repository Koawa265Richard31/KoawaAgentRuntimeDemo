package com.koawa.agent.agent.research;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R003-4 experiment: compares recovery strategies at the fixed crash point
 * where an external write succeeded but its result was not durably recorded.
 */
class ToolRecoveryStrategyExperimentTest {

    private static final String OPERATION_ID = "tool-call-r003-4";

    @Test
    void shouldDuplicateEffectWhenStepOnlyRecoveryBlindlyRetries() {
        ExperimentResult result = simulate(
                RecoveryStrategy.STEP_ONLY_RETRY);

        assertEquals(2, result.executionAttempts());
        assertEquals(2, result.externalEffects());
        assertEquals(0, result.ledgerWrites());
        assertEquals(0, result.resultQueries());
        assertTrue(result.automaticallyCompleted());
        assertEquals(LedgerState.NONE, result.finalLedgerState());
    }

    @Test
    void shouldApplyOneEffectWhenStableIdempotencyKeyIsReused() {
        ExperimentResult result = simulate(
                RecoveryStrategy.IDEMPOTENCY_KEY_RETRY);

        assertEquals(2, result.executionAttempts());
        assertEquals(1, result.externalEffects());
        assertEquals(0, result.ledgerWrites());
        assertEquals(0, result.resultQueries());
        assertTrue(result.automaticallyCompleted());
        assertEquals(LedgerState.NONE, result.finalLedgerState());
    }

    @Test
    void shouldShowThatLedgerAloneDoesNotMakeBlindRetrySafe() {
        ExperimentResult result = simulate(
                RecoveryStrategy.LEDGER_BLIND_RETRY);

        assertEquals(2, result.executionAttempts());
        assertEquals(2, result.externalEffects());
        assertEquals(3, result.ledgerWrites());
        assertEquals(0, result.resultQueries());
        assertTrue(result.automaticallyCompleted());
        assertEquals(LedgerState.SUCCEEDED, result.finalLedgerState());
    }

    @Test
    void shouldReuseExternallyObservedResultWithoutRetryingWrite() {
        ExperimentResult result = simulate(
                RecoveryStrategy.LEDGER_QUERY_THEN_REUSE);

        assertEquals(1, result.executionAttempts());
        assertEquals(1, result.externalEffects());
        assertEquals(3, result.ledgerWrites());
        assertEquals(1, result.resultQueries());
        assertTrue(result.automaticallyCompleted());
        assertEquals(LedgerState.SUCCEEDED, result.finalLedgerState());
    }

    @Test
    void shouldStopAtUnknownOutcomeInsteadOfRiskingDuplicateWrite() {
        ExperimentResult result = simulate(
                RecoveryStrategy.LEDGER_STOP_FOR_MANUAL);

        assertEquals(1, result.executionAttempts());
        assertEquals(1, result.externalEffects());
        assertEquals(3, result.ledgerWrites());
        assertEquals(0, result.resultQueries());
        assertFalse(result.automaticallyCompleted());
        assertEquals(
                LedgerState.OUTCOME_UNKNOWN,
                result.finalLedgerState());
    }

    private ExperimentResult simulate(RecoveryStrategy strategy) {
        SimulatedExternalSystem externalSystem =
                new SimulatedExternalSystem();
        SimulatedToolLedger ledger = new SimulatedToolLedger();
        int executionAttempts = 0;
        int resultQueries = 0;

        if (strategy.usesLedger()) {
            ledger.transitionTo(LedgerState.PREPARED);
            ledger.transitionTo(LedgerState.RUNNING);
        }

        executionAttempts++;
        executeExternalWrite(strategy, externalSystem);

        // Injected crash: external write succeeded, but neither the completed
        // Agent Step nor a SUCCEEDED ledger record was durably stored.

        boolean automaticallyCompleted;
        switch (strategy) {
            case STEP_ONLY_RETRY -> {
                executionAttempts++;
                externalSystem.append(OPERATION_ID);
                automaticallyCompleted = true;
            }
            case IDEMPOTENCY_KEY_RETRY -> {
                executionAttempts++;
                externalSystem.applyOnce(OPERATION_ID);
                automaticallyCompleted = true;
            }
            case LEDGER_BLIND_RETRY -> {
                executionAttempts++;
                externalSystem.append(OPERATION_ID);
                ledger.transitionTo(LedgerState.SUCCEEDED);
                automaticallyCompleted = true;
            }
            case LEDGER_QUERY_THEN_REUSE -> {
                resultQueries++;
                boolean resultExists =
                        externalSystem.contains(OPERATION_ID);
                if (!resultExists) {
                    throw new AssertionError(
                            "injected successful write must be queryable");
                }
                ledger.transitionTo(LedgerState.SUCCEEDED);
                automaticallyCompleted = true;
            }
            case LEDGER_STOP_FOR_MANUAL -> {
                ledger.transitionTo(LedgerState.OUTCOME_UNKNOWN);
                automaticallyCompleted = false;
            }
            default -> throw new IllegalStateException(
                    "unsupported strategy: " + strategy);
        }

        return new ExperimentResult(
                executionAttempts,
                externalSystem.effectCount(),
                ledger.writeCount(),
                resultQueries,
                automaticallyCompleted,
                ledger.currentState());
    }

    private void executeExternalWrite(
            RecoveryStrategy strategy,
            SimulatedExternalSystem externalSystem
    ) {
        if (strategy == RecoveryStrategy.IDEMPOTENCY_KEY_RETRY) {
            externalSystem.applyOnce(OPERATION_ID);
            return;
        }
        externalSystem.append(OPERATION_ID);
    }

    private enum RecoveryStrategy {
        STEP_ONLY_RETRY(false),
        IDEMPOTENCY_KEY_RETRY(false),
        LEDGER_BLIND_RETRY(true),
        LEDGER_QUERY_THEN_REUSE(true),
        LEDGER_STOP_FOR_MANUAL(true);

        private final boolean usesLedger;

        RecoveryStrategy(boolean usesLedger) {
            this.usesLedger = usesLedger;
        }

        boolean usesLedger() {
            return usesLedger;
        }
    }

    private enum LedgerState {
        NONE,
        PREPARED,
        RUNNING,
        SUCCEEDED,
        OUTCOME_UNKNOWN
    }

    private static final class SimulatedExternalSystem {

        private final List<String> operationIds = new ArrayList<>();

        void append(String operationId) {
            operationIds.add(operationId);
        }

        void applyOnce(String operationId) {
            if (!contains(operationId)) {
                append(operationId);
            }
        }

        boolean contains(String operationId) {
            return operationIds.contains(operationId);
        }

        int effectCount() {
            return operationIds.size();
        }
    }

    private static final class SimulatedToolLedger {

        private final List<LedgerState> writes = new ArrayList<>();

        void transitionTo(LedgerState state) {
            writes.add(state);
        }

        int writeCount() {
            return writes.size();
        }

        LedgerState currentState() {
            if (writes.isEmpty()) {
                return LedgerState.NONE;
            }
            return writes.get(writes.size() - 1);
        }
    }

    private record ExperimentResult(
            int executionAttempts,
            int externalEffects,
            int ledgerWrites,
            int resultQueries,
            boolean automaticallyCompleted,
            LedgerState finalLedgerState
    ) {
    }
}
