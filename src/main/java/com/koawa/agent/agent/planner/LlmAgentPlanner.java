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

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentFailureType;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.exception.AgentFailureException;
import com.koawa.agent.agent.parser.AgentActionParser;
import com.koawa.agent.framework.convention.ChatRequest;
import com.koawa.agent.infra.chat.LLMService;
import com.koawa.agent.agent.mcp.McpToolRegistry;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Objects;

public class LlmAgentPlanner implements AgentPlanner {

    private final LLMService llmService;
    private final AgentActionParser actionParser;
    private final AgentRequestAssembler requestAssembler;
    private final McpToolRegistry toolRegistry;

    public LlmAgentPlanner(
            LLMService llmService,
            AgentActionParser actionParser,
            AgentRequestAssembler requestAssembler,
            McpToolRegistry toolRegistry
    ) {
        this.llmService = Objects.requireNonNull(
                llmService,
                "llmService cannot be null"
        );
        this.actionParser = Objects.requireNonNull(
                actionParser,
                "actionParser cannot be null"
        );
        this.requestAssembler = Objects.requireNonNull(
                requestAssembler,
                "requestAssembler cannot be null"
        );
        this.toolRegistry = Objects.requireNonNull(
                toolRegistry,
                "toolRegistry cannot be null"
        );
    }

    @Override
    public AgentAction plan(AgentState state) {

        List<McpSchema.Tool> tools = toolRegistry.listAllTools();

        ChatRequest request = requestAssembler.assemble(state, tools);

        String rawAction;
        try {
            rawAction = llmService.chat(request);
        } catch (RuntimeException exception) {
            throw new AgentFailureException(
                    AgentFailureType.MODEL_CALL_FAILED,
                    exception.getMessage(),
                    exception
            );
        }

        if (rawAction == null || rawAction.isBlank()) {
            throw new AgentFailureException(
                    AgentFailureType.EMPTY_MODEL_RESPONSE,
                    "Agent planner LLM returned a blank action"
            );
        }

        try {
            return actionParser.parse(rawAction);
        } catch (IllegalArgumentException exception) {
            throw new AgentFailureException(
                    AgentFailureType.INVALID_ACTION_RESPONSE,
                    exception.getMessage(),
                    exception
            );
        }
    }
}
