package com.koawa.agent.agent.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskStatusTest {

    private static final Map<AgentTaskStatus, Set<AgentTaskStatus>> ALLOWED_TRANSITIONS = Map.of(
            AgentTaskStatus.RUNNING, EnumSet.allOf(AgentTaskStatus.class),
            AgentTaskStatus.WAITING_FOR_INPUT, EnumSet.of(
                    AgentTaskStatus.WAITING_FOR_INPUT,
                    AgentTaskStatus.RUNNING,
                    AgentTaskStatus.CANCELLED,
                    AgentTaskStatus.TIMED_OUT),
            AgentTaskStatus.WAITING_FOR_APPROVAL, EnumSet.of(
                    AgentTaskStatus.WAITING_FOR_APPROVAL,
                    AgentTaskStatus.RUNNING,
                    AgentTaskStatus.CANCELLED,
                    AgentTaskStatus.TIMED_OUT),
            AgentTaskStatus.COMPLETED, EnumSet.of(AgentTaskStatus.COMPLETED),
            AgentTaskStatus.FAILED, EnumSet.of(AgentTaskStatus.FAILED),
            AgentTaskStatus.CANCELLED, EnumSet.of(AgentTaskStatus.CANCELLED),
            AgentTaskStatus.TIMED_OUT, EnumSet.of(AgentTaskStatus.TIMED_OUT));

    @Test
    void shouldFollowLifecycleTransitionMatrix() {
        assertEquals(
                EnumSet.allOf(AgentTaskStatus.class),
                ALLOWED_TRANSITIONS.keySet(),
                "transition matrix must cover every task status");

        for (AgentTaskStatus source : AgentTaskStatus.values()) {
            for (AgentTaskStatus target : AgentTaskStatus.values()) {
                assertEquals(
                        ALLOWED_TRANSITIONS.get(source).contains(target),
                        source.canTransitionTo(target),
                        () -> "unexpected transition result: " + source + " -> " + target);
            }
        }
    }

    @Test
    void shouldClassifyWaitingStatuses() {
        for (AgentTaskStatus status : AgentTaskStatus.values()) {
            boolean expected = status == AgentTaskStatus.WAITING_FOR_INPUT
                    || status == AgentTaskStatus.WAITING_FOR_APPROVAL;
            assertEquals(expected, status.isWaiting(), status.name());
        }
    }

    @Test
    void shouldClassifyTerminalStatuses() {
        assertFalse(AgentTaskStatus.RUNNING.isTerminal());
        assertFalse(AgentTaskStatus.WAITING_FOR_INPUT.isTerminal());
        assertFalse(AgentTaskStatus.WAITING_FOR_APPROVAL.isTerminal());
        assertTrue(AgentTaskStatus.COMPLETED.isTerminal());
        assertTrue(AgentTaskStatus.FAILED.isTerminal());
        assertTrue(AgentTaskStatus.CANCELLED.isTerminal());
        assertTrue(AgentTaskStatus.TIMED_OUT.isTerminal());
    }

    @Test
    void shouldRejectNullTransitionTarget() {
        for (AgentTaskStatus status : AgentTaskStatus.values()) {
            assertFalse(status.canTransitionTo(null), status.name());
        }
    }
}
