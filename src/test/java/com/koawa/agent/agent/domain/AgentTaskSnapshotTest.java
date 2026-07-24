package com.koawa.agent.agent.domain;

import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.MessageSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTaskSnapshotTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-24T06:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-24T06:01:00Z");
    private static final Instant DEADLINE_AT = Instant.parse("2026-07-24T06:05:00Z");

    @Test
    void shouldDefensivelyCopyCollections() {
        List<StepSnapshot> steps = new ArrayList<>(List.of(step(0)));
        List<MessageSnapshot> history = new ArrayList<>(List.of(
                new MessageSnapshot(ChatMessage.Role.USER, "previous question")));
        Map<String, String> recoveryContext = new HashMap<>(Map.of(
                "planningRecoveryAttempts",
                "1"));
        Map<String, String> interruptContext = new HashMap<>(Map.of(
                "questionType",
                "clarification"));
        PendingInterrupt pendingInterrupt = new PendingInterrupt(
                "interrupt-1",
                InterruptType.USER_INPUT,
                "Please clarify",
                interruptContext,
                CREATED_AT);

        AgentTaskSnapshot snapshot = snapshot(
                AgentTaskStatus.WAITING_FOR_INPUT,
                steps,
                history,
                recoveryContext,
                pendingInterrupt);

        steps.clear();
        history.clear();
        recoveryContext.clear();
        interruptContext.clear();

        assertEquals(1, snapshot.steps().size());
        assertEquals(1, snapshot.historySnapshot().size());
        assertEquals("1", snapshot.recoveryContext().get("planningRecoveryAttempts"));
        assertEquals(
                "clarification",
                snapshot.pendingInterrupt().context().get("questionType"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.steps().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.historySnapshot().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.recoveryContext().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.pendingInterrupt().context().clear());
    }

    @Test
    void shouldRequireMatchingInterruptForWaitingStatus() {
        PendingInterrupt inputInterrupt = interrupt(InterruptType.USER_INPUT);
        PendingInterrupt approvalInterrupt = interrupt(InterruptType.APPROVAL);

        assertEquals(
                inputInterrupt,
                snapshot(
                        AgentTaskStatus.WAITING_FOR_INPUT,
                        List.of(),
                        List.of(),
                        Map.of(),
                        inputInterrupt).pendingInterrupt());
        assertEquals(
                approvalInterrupt,
                snapshot(
                        AgentTaskStatus.WAITING_FOR_APPROVAL,
                        List.of(),
                        List.of(),
                        Map.of(),
                        approvalInterrupt).pendingInterrupt());

        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(
                        AgentTaskStatus.WAITING_FOR_INPUT,
                        List.of(),
                        List.of(),
                        Map.of(),
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(
                        AgentTaskStatus.WAITING_FOR_APPROVAL,
                        List.of(),
                        List.of(),
                        Map.of(),
                        inputInterrupt));
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(
                        AgentTaskStatus.RUNNING,
                        List.of(),
                        List.of(),
                        Map.of(),
                        approvalInterrupt));
    }

    @Test
    void shouldRequireCursorToMatchContiguousCompletedSteps() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTaskSnapshot(
                        AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                        "task-1",
                        "conversation-1",
                        "user-1",
                        0,
                        AgentTaskStatus.RUNNING,
                        "question",
                        2,
                        4,
                        DEADLINE_AT,
                        List.of(step(0)),
                        List.of(),
                        Map.of(),
                        null,
                        CREATED_AT,
                        UPDATED_AT));

        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot(
                        AgentTaskStatus.RUNNING,
                        List.of(step(1)),
                        List.of(),
                        Map.of(),
                        null));
    }

    @Test
    void shouldRejectInvalidIdentityVersionAndTimeRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> baseSnapshot(0, "task-1", 0, CREATED_AT, UPDATED_AT));
        assertThrows(
                IllegalArgumentException.class,
                () -> baseSnapshot(1, " ", 0, CREATED_AT, UPDATED_AT));
        assertThrows(
                IllegalArgumentException.class,
                () -> baseSnapshot(1, "task-1", -1, CREATED_AT, UPDATED_AT));
        assertThrows(
                IllegalArgumentException.class,
                () -> baseSnapshot(1, "task-1", 0, UPDATED_AT, CREATED_AT));
    }

    @Test
    void shouldRejectInvalidStepBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StepSnapshot(
                        -1,
                        AgentActionType.CALL_MCP_TOOL,
                        null,
                        "{}",
                        null,
                        "{}",
                        false,
                        "error"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StepSnapshot(
                        0,
                        AgentActionType.CALL_MCP_TOOL,
                        null,
                        " ",
                        null,
                        "{}",
                        false,
                        "error"));
    }

    private AgentTaskSnapshot snapshot(
            AgentTaskStatus status,
            List<StepSnapshot> steps,
            List<MessageSnapshot> history,
            Map<String, String> recoveryContext,
            PendingInterrupt pendingInterrupt
    ) {
        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                "task-1",
                "conversation-1",
                "user-1",
                0,
                status,
                "question",
                steps.size(),
                4,
                DEADLINE_AT,
                steps,
                history,
                recoveryContext,
                pendingInterrupt,
                CREATED_AT,
                UPDATED_AT);
    }

    private AgentTaskSnapshot baseSnapshot(
            int schemaVersion,
            String taskId,
            long revision,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new AgentTaskSnapshot(
                schemaVersion,
                taskId,
                "conversation-1",
                "user-1",
                revision,
                AgentTaskStatus.RUNNING,
                "question",
                0,
                4,
                DEADLINE_AT,
                List.of(),
                List.of(),
                Map.of(),
                null,
                createdAt,
                updatedAt);
    }

    private StepSnapshot step(int index) {
        return new StepSnapshot(
                index,
                AgentActionType.CALL_MCP_TOOL,
                "use tool",
                "{\"name\":\"search\"}",
                "result",
                "{\"latencyMs\":10}",
                true,
                null);
    }

    private PendingInterrupt interrupt(InterruptType type) {
        return new PendingInterrupt(
                "interrupt-1",
                type,
                "Please continue",
                Map.of("toolName", "filesystem.write"),
                CREATED_AT);
    }
}
