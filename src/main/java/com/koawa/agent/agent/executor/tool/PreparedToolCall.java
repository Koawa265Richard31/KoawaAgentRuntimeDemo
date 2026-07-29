package com.koawa.agent.agent.executor.tool;

import com.koawa.agent.agent.mcp.McpToolExecutor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PreparedToolCall(
        String toolId,
        Map<String, Object> parameters,
        McpToolExecutor executor
) {
    public PreparedToolCall {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException(
                    "toolId must be a non-blank string"
            );
        }

        toolId = toolId.trim();

        parameters = Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Objects.requireNonNull(
                                parameters,
                                "parameters cannot be null"
                        )
                )
        );
        Objects.requireNonNull(executor, "executor cannot be null");
    }
}
