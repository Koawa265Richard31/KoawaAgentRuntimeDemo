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

package com.koawa.agent.agent.executor.policy;

import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.executor.tool.PreparedToolCall;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class AllowListAgentExecutionPolicy
        implements AgentExecutionPolicy {

    private final Set<String> allowedToolIds;

    public AllowListAgentExecutionPolicy(Set<String> allowedToolIds) {
        Objects.requireNonNull(
                allowedToolIds,
                "allowedToolIds cannot be null"
        );

        this.allowedToolIds = allowedToolIds.stream()
                .map(toolId -> {
                    if (toolId == null || toolId.isBlank()) {
                        throw new IllegalArgumentException(
                                "allowed toolId cannot be blank"
                        );
                    }
                    return toolId.trim();
                })
                .collect(Collectors.toUnmodifiableSet());
    }


    @Override
    public ToolExecutionDecision evaluate(
            PreparedToolCall preparedToolCall,
            AgentState state
    ) {
        Objects.requireNonNull(
                preparedToolCall,
                "preparedToolCall cannot be null"
        );
        Objects.requireNonNull(state, "state cannot be null");

        if (allowedToolIds.contains(preparedToolCall.toolId())) {
            return ToolExecutionDecision.allow();
        }

        return ToolExecutionDecision.deny(
                "Tool is not in allowlist: "
                        + preparedToolCall.toolId()
        );
    }
}
