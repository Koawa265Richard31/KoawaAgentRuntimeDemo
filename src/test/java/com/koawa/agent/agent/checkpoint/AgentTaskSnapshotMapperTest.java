package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.snapshot.AgentTaskSnapshotMapper;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentConversationTurnInput;
import com.koawa.agent.agent.domain.AgentFailureType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStep;
import com.koawa.agent.agent.domain.AgentStopReason;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.agent.exception.AgentTaskSnapshotMappingException;
import com.koawa.agent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTaskSnapshotMapperTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-07-24T06:00:00Z");
    private static final Instant UPDATED_AT =
            Instant.parse("2026-07-24T06:01:00Z");
    private static final Instant DEADLINE_AT =
            Instant.parse("2026-07-24T06:05:00Z");

    private final AgentTaskSnapshotMapper mapper =
            new AgentTaskSnapshotMapper();

    @Test
    void shouldRoundTripCompleteRuntimeState() {
        AgentState original = state();

        AgentTaskSnapshot snapshot = mapper.toSnapshot(
                original,
                AgentTaskStatus.COMPLETED,
                3,
                null,
                CREATED_AT,
                UPDATED_AT);
        AgentState restored = mapper.toState(snapshot);

        assertEquals(3, snapshot.revision());
        assertEquals(AgentTaskStatus.COMPLETED, snapshot.status());
        assertEquals(original.getConversationId(), restored.getConversationId());
        assertEquals(original.getTaskId(), restored.getTaskId());
        assertEquals(original.getUserId(), restored.getUserId());
        assertEquals(original.getOriginalQuestion(), restored.getOriginalQuestion());
        assertEquals(
                original.getCurrentTurnInput(),
                restored.getCurrentTurnInput()
        );
        assertEquals(3, restored.getCheckpointRevision());
        assertEquals(original.getCurrentStep(), restored.getCurrentStep());
        assertEquals(original.getMaxSteps(), restored.getMaxSteps());
        assertEquals(original.getDeadlineAt(), restored.getDeadlineAt());
        assertEquals(original.getSteps(), restored.getSteps());
        assertEquals(original.getHistorySnapshot(), restored.getHistorySnapshot());
        assertEquals(original.getFinalAnswer(), restored.getFinalAnswer());
        assertEquals(original.getStopReason(), restored.getStopReason());
        assertEquals(original.getFailureType(), restored.getFailureType());
        assertEquals(original.getErrorMessage(), restored.getErrorMessage());
        assertEquals(
                original.getPlanningRecoveryAttempts(),
                restored.getPlanningRecoveryAttempts());
    }

    @Test
    void shouldDetachSnapshotAndRestoredStateFromMutableRuntimeObjects() {
        AgentState original = state();

        AgentTaskSnapshot snapshot = mapper.toSnapshot(
                original,
                AgentTaskStatus.COMPLETED,
                0,
                null,
                CREATED_AT,
                UPDATED_AT);

        original.getSteps().get(0).getAction()
                .getArguments().put("mutated", true);
        original.getSteps().get(0).getObservation()
                .getMetadata().put("mutated", true);
        original.getHistorySnapshot().get(0).setContent("mutated");

        AgentState restored = mapper.toState(snapshot);

        assertEquals(
                Map.of("query", "status"),
                restored.getSteps().get(0).getAction().getArguments());
        assertEquals(
                Map.of("server", "order-mcp"),
                restored.getSteps().get(0).getObservation().getMetadata());
        assertEquals(
                "previous question",
                restored.getHistorySnapshot().get(0).getContent());

        restored.getSteps().get(0).getAction()
                .getArguments().put("restoredMutation", true);
        restored.getHistorySnapshot().get(0).setContent("restored mutation");

        AgentState restoredAgain = mapper.toState(snapshot);
        assertEquals(
                Map.of("query", "status"),
                restoredAgain.getSteps().get(0).getAction().getArguments());
        assertEquals(
                "previous question",
                restoredAgain.getHistorySnapshot().get(0).getContent());
        assertNotSame(
                restored.getSteps().get(0),
                restoredAgain.getSteps().get(0));
    }

    @Test
    void shouldRejectIncompleteOrInconsistentRuntimeStep() {
        AgentState incomplete = state();
        incomplete.getSteps().get(0).setObservation(null);

        assertThrows(
                AgentTaskSnapshotMappingException.class,
                () -> mapper.toSnapshot(
                        incomplete,
                        AgentTaskStatus.RUNNING,
                        0,
                        null,
                        CREATED_AT,
                        UPDATED_AT));

        AgentState inconsistent = state();
        inconsistent.getSteps().get(0).getObservation()
                .setActionType(AgentActionType.FINAL_ANSWER);

        assertThrows(
                AgentTaskSnapshotMappingException.class,
                () -> mapper.toSnapshot(
                        inconsistent,
                        AgentTaskStatus.RUNNING,
                        0,
                        null,
                        CREATED_AT,
                        UPDATED_AT));
    }

    @Test
    void shouldRejectMalformedStepJsonDuringRestore() {
        AgentTaskSnapshot snapshot = new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                "task-1",
                "conversation-1",
                "user-1",
                0,
                AgentTaskStatus.RUNNING,
                "question",
                1,
                4,
                DEADLINE_AT,
                List.of(new StepSnapshot(
                        0,
                        AgentActionType.CALL_MCP_TOOL,
                        "use tool",
                        "not-json",
                        "result",
                        "{}",
                        true,
                        null)),
                List.of(),
                Map.of(),
                null,
                CREATED_AT,
                UPDATED_AT);

        assertThrows(
                AgentTaskSnapshotMappingException.class,
                () -> mapper.toState(snapshot));
    }

    @Test
    void shouldRejectInvalidRecoveryContextDuringRestore() {
        AgentTaskSnapshot snapshot = new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                "task-1",
                "conversation-1",
                "user-1",
                0,
                AgentTaskStatus.RUNNING,
                "question",
                0,
                4,
                DEADLINE_AT,
                List.of(),
                List.of(),
                Map.of(
                        "planningRecoveryAttempts",
                        "invalid"),
                null,
                CREATED_AT,
                UPDATED_AT);

        assertThrows(
                AgentTaskSnapshotMappingException.class,
                () -> mapper.toState(snapshot));
    }

    @Test
    void shouldFallbackOnlyForLegacyUnconsumedInput() {
        AgentState restored = mapper.toState(emptySnapshot(Map.of()));

        assertEquals(
                AgentConversationTurnInput.originalQuestion("question"),
                restored.getCurrentTurnInput()
        );
        assertThrows(
                AgentTaskSnapshotMappingException.class,
                () -> mapper.toState(emptySnapshot(Map.of(
                        "consumedUserInputStep",
                        "0"
                )))
        );
    }

    @Test
    void shouldRejectPartialCurrentTurnInputContext() {
        assertThrows(
                AgentTaskSnapshotMappingException.class,
                () -> mapper.toState(emptySnapshot(Map.of(
                        "currentTurnInputType",
                        "INTERRUPT_REPLY",
                        "currentTurnInputContent",
                        "reply"
                )))
        );
        assertThrows(
                AgentTaskSnapshotMappingException.class,
                () -> mapper.toState(emptySnapshot(Map.of(
                        "currentTurnInputContent",
                        "reply"
                )))
        );
    }

    private AgentTaskSnapshot emptySnapshot(
            Map<String, String> recoveryContext
    ) {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                "task-legacy",
                "conversation-1",
                "user-1",
                0,
                AgentTaskStatus.RUNNING,
                "question",
                0,
                4,
                DEADLINE_AT,
                List.of(),
                List.of(),
                recoveryContext,
                null,
                CREATED_AT,
                UPDATED_AT
        );
    }

    private AgentState state() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("query", "status");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("server", "order-mcp");

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .thought("query order")
                .arguments(arguments)
                .build();
        AgentObservation observation = AgentObservation.builder()
                .actionType(AgentActionType.CALL_MCP_TOOL)
                .content("waiting for shipment")
                .metadata(metadata)
                .success(true)
                .build();

        return AgentState.builder()
                .conversationId("conversation-1")
                .taskId("task-1")
                .userId("user-1")
                .originalQuestion("query order")
                .currentTurnInput(
                        AgentConversationTurnInput.interruptReply(
                                "repository-a",
                                "interrupt-1"
                        )
                )
                .currentStep(1)
                .maxSteps(4)
                .deadlineAt(DEADLINE_AT)
                .steps(new ArrayList<>(List.of(AgentStep.builder()
                        .stepIndex(0)
                        .action(action)
                        .observation(observation)
                        .build())))
                .historySnapshot(new ArrayList<>(List.of(
                        ChatMessage.user("previous question"),
                        ChatMessage.assistant("previous answer"))))
                .finalAnswer("order result")
                .stopReason(AgentStopReason.FINAL_ANSWER)
                .failureType(AgentFailureType.ACTION_EXECUTION_FAILED)
                .errorMessage("recovered tool failure")
                .planningRecoveryAttempts(2)
                .build();
    }
}
