/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.koawa.agent.agent.executor.handler;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.executor.AgentActionHandler;
import com.koawa.agent.agent.executor.policy.AgentExecutionPolicy;
import com.koawa.agent.agent.executor.policy.ToolExecutionDecision;
import com.koawa.agent.agent.executor.tool.PreparedToolCall;
import com.koawa.agent.agent.mcp.McpToolExecutor;
import com.koawa.agent.agent.mcp.McpToolRegistry;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class CallMcpToolActionHandler implements AgentActionHandler {

    private final McpToolRegistry toolRegistry;
    private final AgentExecutionPolicy executionPolicy;

    public CallMcpToolActionHandler(
            McpToolRegistry toolRegistry,
            AgentExecutionPolicy executionPolicy
    ) {
        this.toolRegistry = Objects.requireNonNull(
                toolRegistry,
                "toolRegistry cannot be null"
        );
        this.executionPolicy = Objects.requireNonNull(
                executionPolicy,
                "executionPolicy cannot be null"
        );
    }

    @Override
    public AgentActionType supportedAction() {
        return AgentActionType.CALL_MCP_TOOL;
    }

    @Override
    public AgentObservation execute(
            AgentAction action,
            AgentState state
    ) {
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(state, "state cannot be null");

        if (action.getType() != AgentActionType.CALL_MCP_TOOL) {
            throw new IllegalArgumentException(
                    "Unsupported action type: " + action.getType()
            );
        }

        String toolId = parseToolId(action);
        Map<String, Object> parameters = parseParameters(action);

        McpToolExecutor toolExecutor = toolRegistry.getExecutor(toolId).orElse(null);
        if (toolExecutor == null) {
            return failedObservation(
                    toolId,
                    "MCP tool not found: " + toolId
            );
        }

        PreparedToolCall preparedCall = new PreparedToolCall(
                toolId,
                parameters,
                toolExecutor
        );

        ToolExecutionDecision decision = Objects.requireNonNull(
                executionPolicy.evaluate(preparedCall, state),
                "executionPolicy returned null decision"
        );

        if (!decision.allowed()) {
            return failedObservation(
                    preparedCall.toolId(),
                    "MCP tool execution denied by policy: " + decision.reason()
            );
        }

        try {
            McpSchema.CallToolResult result = preparedCall.executor().execute(
                    preparedCall.parameters()
            );

            if (result == null) {
                return failedObservation(
                        preparedCall.toolId(),
                        "MCP tool returned null result: "
                                + preparedCall.toolId()
                );
            }

            String content = extractTextContent(result);
            boolean isError = Boolean.TRUE.equals(result.isError());

            return AgentObservation.builder()
                    .actionType(AgentActionType.CALL_MCP_TOOL)
                    .content(content)
                    .success(!isError)
                    .metadata(Map.of(
                            "toolId", preparedCall.toolId()
                    ))
                    .errorMessage(
                            isError
                                    ? parseErrorMessage(content)
                                    : null
                    )
                    .build();
        } catch (RuntimeException exception) {
            return failedObservation(
                    preparedCall.toolId(),
                    "MCP tool execution failed: "
                            + Objects.toString(exception.getMessage(),
                            exception.getClass().getSimpleName()
                    )
            );
        }
    }

    private String parseToolId(AgentAction action) {
        Map<String, Object> arguments = action.getArguments();

        if (arguments == null) {
            throw new IllegalArgumentException(
                    "CALL_MCP_TOOL argument cannot be null"
            );
        }

        Object toolIdValue = arguments.get("toolId");

        if (!(toolIdValue instanceof String toolId) || toolId.isBlank()) {
            throw new IllegalArgumentException(
                    "CALL_MCP_TOOL toolId must be a non-blank string"
            );
        }

        return toolId.trim();
    }

    private Map<String, Object> parseParameters(AgentAction action) {
        Map<String, Object> arguments = action.getArguments();
        Object paramsValue = arguments.get("params");

        if (paramsValue == null) {
            return new HashMap<>();
        }

        if (!(paramsValue instanceof Map<?, ?> rawParams)) {
            throw new IllegalArgumentException(
                    "CALL_MCP_TOOL params must be an object"
            );
        }

        Map<String, Object> parameters = new HashMap<>();

        for (Map.Entry<?, ?> entry : rawParams.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "CALL_MCP_TOOL parameter name must be a string"
                );
            }
            parameters.put(key, entry.getValue());
        }

        return parameters;
    }

    private String parseErrorMessage(String content) {
        return content.isBlank()
                ? "MCP tool returned an error"
                : content;
    }

    private AgentObservation failedObservation(String toolId, String errorMessage) {
        return AgentObservation.builder()
                .actionType(AgentActionType.CALL_MCP_TOOL)
                .success(false)
                .errorMessage(errorMessage)
                .content("")
                .metadata(Map.of(
                        "toolId", toolId
                ))
                .build();
    }

    private String extractTextContent(McpSchema.CallToolResult result) {
        if (result.content() == null) {
            return "";
        }

        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
