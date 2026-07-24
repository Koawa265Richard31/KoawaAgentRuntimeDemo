package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStep;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCheckpointServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-24T06:00:00Z");
    private static final Instant DEADLINE_AT =
            Instant.parse("2026-07-24T06:05:00Z");

    private final InMemoryAgentCheckpointStore store =
            new InMemoryAgentCheckpointStore();
    private final MutableClock clock = new MutableClock(CREATED_AT);
    private final AgentCheckpointService service =
            new AgentCheckpointService(
                    store,
                    new AgentTaskSnapshotMapper(),
                    clock);

    @Test
    void shouldCreateInitialRunningCheckpoint() {
        AgentTaskSnapshot created = service.create(initialState());

        assertEquals(0, created.revision());
        assertEquals(AgentTaskStatus.RUNNING, created.status());
        assertEquals(CREATED_AT, created.createdAt());
        assertEquals(CREATED_AT, created.updatedAt());
        assertEquals(created, store.load("task-1").orElseThrow());
    }

    @Test
    void shouldSaveNextRevisionAndPreserveCreationTime() {
        AgentState state = initialState();
        service.create(state);
        completeOneStep(state);
        clock.setInstant(CREATED_AT.plusSeconds(60));
        PendingInterrupt interrupt = new PendingInterrupt(
                "interrupt-1",
                InterruptType.USER_INPUT,
                "Please clarify",
                Map.of(),
                clock.instant());

        AgentTaskSnapshot saved = service.save(
                state,
                AgentTaskStatus.WAITING_FOR_INPUT,
                interrupt);

        assertEquals(1, saved.revision());
        assertEquals(AgentTaskStatus.WAITING_FOR_INPUT, saved.status());
        assertEquals(1, saved.nextStep());
        assertEquals(CREATED_AT, saved.createdAt());
        assertEquals(CREATED_AT.plusSeconds(60), saved.updatedAt());
        assertEquals(saved, store.load("task-1").orElseThrow());
    }

    @Test
    void shouldLoadDetachedRuntimeStateWithoutResumingTask() {
        AgentState state = initialState();
        AgentTaskSnapshot created = service.create(state);

        AgentCheckpointService.LoadedAgentCheckpoint loaded =
                service.load("task-1").orElseThrow();

        assertEquals(created, loaded.snapshot());
        assertEquals(AgentTaskStatus.RUNNING, loaded.snapshot().status());
        assertEquals(state.getTaskId(), loaded.state().getTaskId());
        assertNotSame(state, loaded.state());

        loaded.state().setOriginalQuestion("mutated");
        assertEquals(
                "question",
                service.load("task-1").orElseThrow()
                        .state()
                        .getOriginalQuestion());
    }

    @Test
    void shouldRejectMissingDuplicateAndProgressedInitialTasks() {
        AgentState state = initialState();

        assertFalse(service.load("missing-task").isPresent());
        assertThrows(
                CheckpointNotFoundException.class,
                () -> service.save(
                        state,
                        AgentTaskStatus.RUNNING,
                        null));

        service.create(state);
        assertThrows(
                CheckpointConflictException.class,
                () -> service.create(state));

        AgentState progressed = initialState();
        completeOneStep(progressed);
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentCheckpointService(
                        new InMemoryAgentCheckpointStore(),
                        new AgentTaskSnapshotMapper(),
                        clock).create(progressed));
    }

    private AgentState initialState() {
        return AgentState.builder()
                .conversationId("conversation-1")
                .taskId("task-1")
                .userId("user-1")
                .originalQuestion("question")
                .currentStep(0)
                .maxSteps(4)
                .deadlineAt(DEADLINE_AT)
                .steps(new ArrayList<>())
                .historySnapshot(List.of())
                .build();
    }

    private void completeOneStep(AgentState state) {
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .thought("call tool")
                .arguments(Map.of("query", "status"))
                .build();
        AgentObservation observation = AgentObservation.builder()
                .actionType(AgentActionType.CALL_MCP_TOOL)
                .content("result")
                .metadata(Map.of())
                .success(true)
                .build();
        state.getSteps().add(AgentStep.builder()
                .stepIndex(0)
                .action(action)
                .observation(observation)
                .build());
        state.setCurrentStep(1);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
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
            return instant;
        }
    }
}
