package com.koawa.agent.agent.research;

import com.koawa.agent.agent.checkpoint.AgentCheckpointService;
import com.koawa.agent.agent.checkpoint.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.InMemoryAgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.event.AgentEventSink;
import com.koawa.agent.agent.exception.AgentCheckpointLifecycleException;
import com.koawa.agent.agent.recovery.AgentRecoveryDecision;
import com.koawa.agent.agent.runner.AgentCancellationChecker;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;
import com.koawa.agent.agent.runner.AgentLoopRunner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R003-3 experiment: compares crash recovery for read-only, idempotent-write,
 * and non-idempotent-write tools while keeping the checkpoint failure fixed.
 */
class ToolSideEffectClassCrashExperimentTest {

    private static final Instant NOW =
            Instant.parse("2026-07-28T02:00:00Z");
    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String IDEMPOTENCY_KEY =
            "tool-call-r003-idempotent";

    @Test
    void shouldRepeatReadWithoutMutatingExternalState() {
        AtomicInteger executionAttempts = new AtomicInteger();
        AtomicReference<String> externalValue =
                new AtomicReference<>("catalog-version-1");

        CrashReplayResult result = crashThenReplay(
                "task-r003-read-only",
                () -> {
                    executionAttempts.incrementAndGet();
                    return externalValue.get();
                });

        assertEquals(2, executionAttempts.get());
        assertEquals("catalog-version-1", externalValue.get());
        assertEquals(
                "catalog-version-1",
                result.resumedState()
                        .getSteps()
                        .get(0)
                        .getObservation()
                        .getContent());
    }

    @Test
    void shouldRepeatAttemptButApplyIdempotentWriteOnce() {
        AtomicInteger executionAttempts = new AtomicInteger();
        Map<String, String> externalRecords = new HashMap<>();

        crashThenReplay(
                "task-r003-idempotent-write",
                () -> {
                    executionAttempts.incrementAndGet();
                    externalRecords.putIfAbsent(
                            IDEMPOTENCY_KEY,
                            "created");
                    return externalRecords.get(IDEMPOTENCY_KEY);
                });

        assertEquals(2, executionAttempts.get());
        assertEquals(1, externalRecords.size());
        assertEquals("created", externalRecords.get(IDEMPOTENCY_KEY));
    }

    @Test
    void shouldApplyNonIdempotentWriteTwiceAfterReplay() {
        AtomicInteger executionAttempts = new AtomicInteger();
        List<String> externalRecords = new ArrayList<>();

        crashThenReplay(
                "task-r003-non-idempotent-write",
                () -> {
                    int attempt = executionAttempts.incrementAndGet();
                    externalRecords.add("created-by-attempt-" + attempt);
                    return externalRecords.get(externalRecords.size() - 1);
                });

        assertEquals(2, executionAttempts.get());
        assertEquals(
                List.of(
                        "created-by-attempt-1",
                        "created-by-attempt-2"),
                externalRecords);
    }

    private CrashReplayResult crashThenReplay(
            String taskId,
            ExternalToolOperation operation
    ) {
        InMemoryAgentCheckpointStore store =
                new InMemoryAgentCheckpointStore();
        AgentCheckpointService checkpointService =
                new AgentCheckpointService(
                        store,
                        new AgentTaskSnapshotMapper(),
                        CLOCK);
        AgentState initialState = initialState(taskId);
        checkpointService.create(initialState);

        AgentCheckpointLifecycle failingCheckpoint =
                new AgentCheckpointLifecycle() {
                    @Override
                    public void stepCommitted(AgentState state) {
                        throw new AgentCheckpointLifecycleException(
                                "simulated crash before step persistence",
                                new IllegalStateException(
                                        "checkpoint unavailable"));
                    }
                };

        assertThrows(
                AgentCheckpointLifecycleException.class,
                () -> runner(
                        operation,
                        failingCheckpoint).run(initialState));

        AgentCheckpointService.LoadedAgentCheckpoint loaded =
                checkpointService.load(taskId).orElseThrow();
        assertEquals(0, loaded.snapshot().revision());
        assertEquals(0, loaded.state().getCurrentStep());
        assertEquals(0, loaded.state().getSteps().size());

        AgentState resumedState = loaded.state();
        runner(
                operation,
                AgentCheckpointLifecycle.NOOP).run(resumedState);

        assertEquals(1, resumedState.getCurrentStep());
        assertEquals(1, resumedState.getSteps().size());
        return new CrashReplayResult(resumedState);
    }

    private AgentLoopRunner runner(
            ExternalToolOperation operation,
            AgentCheckpointLifecycle checkpointLifecycle
    ) {
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .thought("execute classified tool")
                .build();

        return new AgentLoopRunner(
                state -> action,
                (plannedAction, state) ->
                        AgentObservation.builder()
                                .actionType(AgentActionType.CALL_MCP_TOOL)
                                .content(operation.execute())
                                .success(true)
                                .build(),
                AgentEventSink.NOOP,
                AgentCancellationChecker.NEVER_CANCELLED,
                failureType -> AgentRecoveryDecision.STOP,
                CLOCK,
                checkpointLifecycle);
    }

    private AgentState initialState(String taskId) {
        return AgentState.builder()
                .conversationId("conversation-r003")
                .taskId(taskId)
                .userId("researcher")
                .originalQuestion("execute a classified tool")
                .currentStep(0)
                .maxSteps(1)
                .deadlineAt(NOW.plusSeconds(60))
                .steps(new ArrayList<>())
                .build();
    }

    @FunctionalInterface
    private interface ExternalToolOperation {

        String execute();
    }

    private record CrashReplayResult(AgentState resumedState) {
    }
}
