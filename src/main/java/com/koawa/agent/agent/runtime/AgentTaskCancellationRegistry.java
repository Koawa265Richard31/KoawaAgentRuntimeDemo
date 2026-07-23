package com.koawa.agent.agent.runtime;

import com.koawa.agent.agent.runner.AgentCancellationChecker;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class AgentTaskCancellationRegistry
        implements AgentCancellationChecker {

    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isCancelled(String taskId) {
        return taskId != null && cancelledTaskIds.contains(taskId);
    }

    public void cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be blank");
        }
        cancelledTaskIds.add(taskId.trim());
    }

    public void clear(String taskId) {
        if (taskId != null) {
            cancelledTaskIds.remove(taskId);
        }
    }
}
