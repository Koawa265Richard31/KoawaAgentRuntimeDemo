package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.query.AgentTaskQueryService;
import com.koawa.agent.agent.checkpoint.query.AgentTaskView;
import com.koawa.agent.agent.checkpoint.snapshot.AgentCheckpointStore;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.InterruptType;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.MessageSnapshot;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.PendingInterrupt;
import com.koawa.agent.agent.domain.AgentTaskSnapshot.StepSnapshot;
import com.koawa.agent.agent.domain.AgentTaskStatus;
import com.koawa.agent.framework.convention.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskQueryServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-01T01:00:00Z");

    private AgentCheckpointStore store;
    private AgentTaskQueryService service;

    @BeforeEach
    void setUp() {
        store = mock(AgentCheckpointStore.class);
        service = new AgentTaskQueryService(store);
    }

    @Test
    void shouldMapWaitingTaskToPublicControlPlaneView() {
        AgentTaskSnapshot snapshot = snapshot(
                "task-1",
                "conversation-1",
                4,
                AgentTaskStatus.WAITING_FOR_INPUT,
                1
        );
        when(store.load("task-1")).thenReturn(Optional.of(snapshot));

        AgentTaskView view = service.findByTaskId("  task-1  ")
                .orElseThrow();

        assertEquals("task-1", view.taskId());
        assertEquals("conversation-1", view.conversationId());
        assertEquals(4, view.revision());
        assertEquals(AgentTaskStatus.WAITING_FOR_INPUT, view.status());
        assertEquals(1, view.nextStep());
        assertEquals(6, view.maxSteps());
        assertEquals(CREATED_AT.plusSeconds(300), view.deadlineAt());
        assertEquals("interrupt-1", view.pendingInterrupt().interruptId());
        assertEquals(
                InterruptType.USER_INPUT,
                view.pendingInterrupt().type()
        );
        assertEquals("Please continue", view.pendingInterrupt().prompt());
        verify(store).load("task-1");
    }

    @Test
    void shouldReturnEmptyWhenTaskDoesNotExist() {
        when(store.load("missing")).thenReturn(Optional.empty());

        assertEquals(
                Optional.empty(),
                service.findByTaskId("missing")
        );
    }

    @Test
    void shouldPreserveStoreOrderForConversationTasks() {
        when(store.list("conversation-1")).thenReturn(List.of(
                snapshot(
                        "task-2",
                        "conversation-1",
                        2,
                        AgentTaskStatus.COMPLETED,
                        1
                ),
                snapshot(
                        "task-1",
                        "conversation-1",
                        0,
                        AgentTaskStatus.RUNNING,
                        0
                )
        ));

        List<AgentTaskView> views =
                service.listByConversationId(" conversation-1 ");

        assertEquals(
                List.of("task-2", "task-1"),
                views.stream().map(AgentTaskView::taskId).toList()
        );
        assertNull(views.get(0).pendingInterrupt());
        assertNull(views.get(1).pendingInterrupt());
        verify(store).list("conversation-1");
    }

    @Test
    void shouldRejectBlankQueryIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.findByTaskId(" ")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.listByConversationId(null)
        );
    }

    @Test
    void shouldRejectTaskViewWithMismatchedInterruptType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTaskView(
                        "task-1",
                        "conversation-1",
                        1,
                        AgentTaskStatus.WAITING_FOR_INPUT,
                        1,
                        6,
                        CREATED_AT.plusSeconds(300),
                        new AgentTaskView.PendingInterruptView(
                                "interrupt-1",
                                InterruptType.APPROVAL,
                                "Approve?",
                                CREATED_AT.plusSeconds(60)
                        ),
                        CREATED_AT,
                        CREATED_AT.plusSeconds(120)
                )
        );
    }

    private AgentTaskSnapshot snapshot(
            String taskId,
            String conversationId,
            long revision,
            AgentTaskStatus status,
            int stepCount
    ) {
        List<StepSnapshot> steps = stepCount == 0
                ? List.of()
                : List.of(new StepSnapshot(
                        0,
                        AgentActionType.ASK_CLARIFICATION,
                        "private thought",
                        "{\"question\":\"continue?\"}",
                        "Please continue",
                        "{\"private\":\"metadata\"}",
                        true,
                        null
                ));
        PendingInterrupt interrupt = status.isWaiting()
                ? new PendingInterrupt(
                        "interrupt-1",
                        status == AgentTaskStatus.WAITING_FOR_INPUT
                                ? InterruptType.USER_INPUT
                                : InterruptType.APPROVAL,
                        "Please continue",
                        Map.of("private", "context"),
                        CREATED_AT.plusSeconds(60)
                )
                : null;

        return new AgentTaskSnapshot(
                AgentTaskSnapshot.CURRENT_SCHEMA_VERSION,
                taskId,
                conversationId,
                "private-user",
                revision,
                status,
                "private original question",
                stepCount,
                6,
                CREATED_AT.plusSeconds(300),
                steps,
                List.of(new MessageSnapshot(
                        ChatMessage.Role.USER,
                        "private history"
                )),
                Map.of("private", "recovery"),
                interrupt,
                CREATED_AT,
                CREATED_AT.plusSeconds(revision + 1)
        );
    }
}
