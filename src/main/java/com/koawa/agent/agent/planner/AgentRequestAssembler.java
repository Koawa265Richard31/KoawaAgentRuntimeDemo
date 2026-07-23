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

package com.koawa.agent.agent.planner;

import com.google.gson.Gson;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.domain.AgentStep;
import com.koawa.agent.framework.convention.ChatMessage;
import com.koawa.agent.framework.convention.ChatRequest;
import com.koawa.agent.agent.prompt.PromptTemplateLoader;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class AgentRequestAssembler {

    private final PromptTemplateLoader promptTemplateLoader;
    private final Gson gson = new Gson();

    public AgentRequestAssembler(
            PromptTemplateLoader promptTemplateLoader
    ) {
        this.promptTemplateLoader = Objects.requireNonNull(
                promptTemplateLoader,
                "promptTemplateLoader cannot be null"
        );
    }

    public ChatRequest assemble(
            AgentState state,
            List<McpSchema.Tool> tools
    ) {
        Objects.requireNonNull(state, "state cannot be null");

        if (state.getOriginalQuestion() == null
                || state.getOriginalQuestion().isBlank()) {
            throw new IllegalArgumentException(
                    "Original question must be a non-blank string"
            );
        }

        Map<String, String> slots = Map.of(
                "original_question", state.getOriginalQuestion().trim(),
                "current_step", String.valueOf(state.getCurrentStep()),
                "max_steps", String.valueOf(state.getMaxSteps()),
                "steps", formatSteps(state),
                "tools", formatTools(tools),
                "recovery_context", formatRecoveryContext(state)
        );

        String prompt = promptTemplateLoader.render(
                "prompt/agent-planner.st",
                slots
        );

        return ChatRequest.builder()
                .messages(buildMessages(state, prompt))
                .temperature(0.1D)
                .thinking(false)
                .deadlineAt(state.getDeadlineAt())
                .build();
    }

    private String formatRecoveryContext(AgentState state) {
        if (state.getPlanningRecoveryAttempts() <= 0
                || state.getFailureType() == null) {
            return "无规划恢复信息";
        }

        String correction = switch (state.getFailureType()) {
            case EMPTY_MODEL_RESPONSE ->
                    "上一次模型响应为空，请仅返回符合协议的 JSON Action";

            case INVALID_ACTION_RESPONSE ->
                    "上一次响应无法解析为合法 Action，"
                            + "请修正 JSON 结构、Action 类型和 arguments";

            default -> "无规划恢复信息";
        };

        return "planningRetryAttempt: "
                + state.getPlanningRecoveryAttempts()
                + "\nfailureType: "
                + state.getFailureType()
                + "\ncorrection: "
                + correction;
    }

    private String formatSteps(AgentState state) {
        List<AgentStep> steps = state.getSteps();

        if (steps == null || steps.isEmpty()) {
            return "无历史步骤";
        }

        StringBuilder builder = new StringBuilder();

        for (AgentStep step : steps) {
            Objects.requireNonNull(
                    step,
                    "step cannot be null"
            );

            AgentAction action = Objects.requireNonNull(
                    step.getAction(),
                    "step action cannot be null"
            );

            AgentObservation observation = Objects.requireNonNull(
                    step.getObservation(),
                    "step observation cannot be null"
            );

            builder.append("Step ")
                    .append(step.getStepIndex())
                    .append('\n');

            builder.append("actionType: ")
                    .append(action.getType())
                    .append('\n');

            builder.append("arguments: ")
                    .append(gson.toJson(
                            action.getArguments() == null
                                    ? Map.of()
                                    : action.getArguments()
                    ))
                    .append('\n');

            builder.append("observationSuccess: ")
                    .append(observation.isSuccess())
                    .append('\n');

            builder.append("observationContent: ")
                    .append(formatNullable(observation.getContent()))
                    .append('\n');

            builder.append("observationError: ")
                    .append(formatNullable(observation.getErrorMessage()))
                    .append("\n\n");
        }

        return builder.toString().trim();
    }

    private String formatTools(List<McpSchema.Tool> tools) {

        if (tools == null || tools.isEmpty()) {
            return "无可用 MCP 工具";
        }

        StringBuilder builder = new StringBuilder();
        int toolIndex = 1;

        for (McpSchema.Tool tool : tools) {
            Objects.requireNonNull(
                    tool,
                    "registered MCP tool cannot be null"
            );

            builder.append("Tool ")
                    .append(toolIndex++)
                    .append('\n');

            builder.append("toolId: ")
                    .append(formatNullable(tool.name()))
                    .append('\n');

            builder.append("description: ")
                    .append(formatNullable(tool.description()))
                    .append('\n');

            builder.append("inputSchema: ")
                    .append(
                            tool.inputSchema() == null
                                    ? "{}"
                                    : gson.toJson(tool.inputSchema())
                    )
                    .append("\n\n");
        }

        return builder.toString().trim();
    }

    private String formatNullable(String value) {
        return value == null || value.isBlank()
                ? "无"
                : value.trim();
    }

    private List<ChatMessage> buildMessages(
            AgentState state,
            String prompt
    ) {
        List<ChatMessage> messages = new ArrayList<>();

        if (state.getHistorySnapshot() != null) {
            messages.addAll(state.getHistorySnapshot());
        }

        messages.add(ChatMessage.user(prompt));
        return List.copyOf(messages);
    }
}
