package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionLeaseSession;
import com.koawa.agent.agent.checkpoint.lease.AgentExecutionPermit;
import com.koawa.agent.agent.checkpoint.lease.InMemoryAgentExecutionLeaseStore;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointService;
import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentFencedCheckpointWriter;
import com.koawa.agent.agent.checkpoint.snapshot.InMemoryAgentCheckpointStore;
import com.koawa.agent.agent.checkpoint.snapshot.PersistentAgentCheckpointLifecycle;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStep;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.runtime.InMemoryAgentConversationStore;
import com.koawa.agent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistentAgentCheckpointLifecycleTest {

    private static final Instant NOW =
            Instant.parse("2026-07-24T06:00:00Z");

    private final InMemoryAgentCheckpointStore store =
            new InMemoryAgentCheckpointStore();
    private final InMemoryAgentConversationStore conversationStore =
            new InMemoryAgentConversationStore();
    private final AgentCheckpointService service =
            new AgentCheckpointService(
                    store,
                    new AgentTaskSnapshotMapper(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    null,
                    conversationStore,
                    TransactionOperations.withoutTransaction());
    private final PersistentAgentCheckpointLifecycle lifecycle =
            new PersistentAgentCheckpointLifecycle(
                    service,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> "interrupt-1");

    @Test
    void shouldPersistInitialStepAndCompletedRevisions() {
        AgentState state = state("task-1");

        lifecycle.initialize(state);
        assertCheckpoint(
                "task-1",
                0,
                AgentTaskStatus.RUNNING,
                0);

        completeOneStep(state);
        lifecycle.stepCommitted(state);
        assertCheckpoint(
                "task-1",
                1,
                AgentTaskStatus.RUNNING,
                1);

        completeTerminalStep(
                state,
                AgentActionType.FINAL_ANSWER,
                "answer"
        );
        lifecycle.stepCommitted(state);
        state.setStopReason(AgentStopReason.FINAL_ANSWER);
        state.setFinalAnswer("answer");
        lifecycle.completed(state);
        assertCheckpoint(
                "task-1",
                3,
                AgentTaskStatus.COMPLETED,
                2);
        assertEquals(
                List.of(
                        ChatMessage.user("question"),
                        ChatMessage.assistant("answer")
                ),
                conversationStore.load("conversation-1", "user-1")
        );
    }

    @Test
    void shouldPersistClarificationAsWaitingInputWithInterrupt() {
        AgentState state = state("task-2");
        lifecycle.initialize(state);
        completeTerminalStep(
                state,
                AgentActionType.ASK_CLARIFICATION,
                "Which order?"
        );
        lifecycle.stepCommitted(state);
        state.setStopReason(AgentStopReason.ASK_CLARIFICATION);
        state.setFinalAnswer("Which order?");

        lifecycle.completed(state);

        AgentTaskSnapshot snapshot =
                store.load("task-2").orElseThrow();
        assertEquals(2, snapshot.revision());
        assertEquals(
                AgentTaskStatus.WAITING_FOR_INPUT,
                snapshot.status());
        assertEquals(
                "interrupt-1",
                snapshot.pendingInterrupt().interruptId());
        assertEquals(
                "Which order?",
                snapshot.pendingInterrupt().prompt());
        assertEquals(
                List.of(
                        ChatMessage.user("question"),
                        ChatMessage.assistant("Which order?")
                ),
                conversationStore.load("conversation-1", "user-1")
        );
    }

    @Test
    void shouldMapEveryNonSuccessfulStopReasonToTaskStatus() {
        Map<AgentStopReason, AgentTaskStatus> expectedStatuses = Map.of(
                AgentStopReason.MAX_STEPS,
                AgentTaskStatus.FAILED,
                AgentStopReason.ERROR,
                AgentTaskStatus.FAILED,
                AgentStopReason.CANCELLED,
                AgentTaskStatus.CANCELLED,
                AgentStopReason.TIMEOUT,
                AgentTaskStatus.TIMED_OUT);

        int index = 0;
        for (Map.Entry<AgentStopReason, AgentTaskStatus> entry
                : expectedStatuses.entrySet()) {
            String taskId = "terminal-task-" + index++;
            AgentState state = state(taskId);
            lifecycle.initialize(state);
            if (entry.getKey() == AgentStopReason.ERROR) {
                // Planning recovery can advance after the last persisted Step.
                state.setPlanningRecoveryAttempts(1);
            }
            state.setStopReason(entry.getKey());

            lifecycle.completed(state);

            assertEquals(
                    entry.getValue(),
                    store.load(taskId).orElseThrow().status());
        }
    }

    @Test
    void shouldUseCurrentLeaseSessionForResumedCheckpointWrites() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryAgentCheckpointStore checkpointStore =
                new InMemoryAgentCheckpointStore();
        AgentTaskSnapshotMapper mapper = new AgentTaskSnapshotMapper();
        InMemoryAgentExecutionLeaseStore leaseStore =
                new InMemoryAgentExecutionLeaseStore(
                        checkpointStore,
                        clock,
                        () -> "resume-owner"
                );
        AgentCheckpointService fencedService =
                new AgentCheckpointService(
                        checkpointStore,
                        mapper,
                        clock,
                        new InMemoryAgentFencedCheckpointWriter(
                                checkpointStore,
                                leaseStore,
                                clock
                        )
                );
        AgentState state = state("resumed-task");
        fencedService.create(state);
        AgentExecutionPermit permit = leaseStore.acquire(
                state.getTaskId(),
                0,
                Duration.ofSeconds(30)
        );

        try (AgentExecutionLeaseSession session =
                     AgentExecutionLeaseSession.start(
                             leaseStore,
                             permit,
                             Duration.ofSeconds(30),
                             Duration.ofSeconds(10)
                     )) {
            PersistentAgentCheckpointLifecycle resumedLifecycle =
                    new PersistentAgentCheckpointLifecycle(
                            fencedService,
                            clock,
                            () -> "interrupt-resume",
                            session
                    );
            completeOneStep(state);

            resumedLifecycle.stepCommitted(state);
        }

        assertEquals(
                1,
                checkpointStore.load("resumed-task")
                        .orElseThrow()
                        .revision()
        );
    }

    private void assertCheckpoint(
            String taskId,
            long revision,
            AgentTaskStatus status,
            int nextStep
    ) {
        AgentTaskSnapshot snapshot = store.load(taskId).orElseThrow();
        assertEquals(revision, snapshot.revision());
        assertEquals(status, snapshot.status());
        assertEquals(nextStep, snapshot.nextStep());
    }

    private AgentState state(String taskId) {
        return AgentState.builder()
                .conversationId("conversation-1")
                .taskId(taskId)
                .userId("user-1")
                .originalQuestion("question")
                .currentStep(0)
                .maxSteps(4)
                .deadlineAt(NOW.plusSeconds(300))
                .steps(new ArrayList<>())
                .historySnapshot(List.of())
                .build();
    }

    private void completeOneStep(AgentState state) {
        completeTerminalStep(
                state,
                AgentActionType.CALL_MCP_TOOL,
                "result"
        );
    }

    private void completeTerminalStep(
            AgentState state,
            AgentActionType actionType,
            String content
    ) {
        AgentAction action = AgentAction.builder()
                .type(actionType)
                .thought("execute step")
                .arguments(Map.of())
                .build();
        AgentObservation observation = AgentObservation.builder()
                .actionType(actionType)
                .content(content)
                .metadata(Map.of())
                .success(true)
                .build();
        state.getSteps().add(AgentStep.builder()
                .stepIndex(state.getCurrentStep())
                .action(action)
                .observation(observation)
                .build());
        state.setCurrentStep(state.getCurrentStep() + 1);
    }
}
