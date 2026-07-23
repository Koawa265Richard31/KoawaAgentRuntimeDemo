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
import com.koawa.agent.agent.mcp.McpToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AllowListAgentExecutionPolicyTest {

    private final McpToolExecutor executor = mock(McpToolExecutor.class);
    private final AgentState state = AgentState.builder().build();

    @Test
    void shouldAllowConfiguredTool() {
        AgentExecutionPolicy policy =
                new AllowListAgentExecutionPolicy(Set.of("sales_query"));

        ToolExecutionDecision decision = policy.evaluate(
                preparedCall("sales_query"),
                state
        );

        assertTrue(decision.allowed());
        assertNull(decision.reason());
    }

    @Test
    void shouldDenyToolOutsideAllowList() {
        AgentExecutionPolicy policy =
                new AllowListAgentExecutionPolicy(Set.of("sales_query"));

        ToolExecutionDecision decision = policy.evaluate(
                preparedCall("weather_query"),
                state
        );

        assertFalse(decision.allowed());
        assertEquals(
                "Tool is not in allowlist: weather_query",
                decision.reason()
        );
    }

    private PreparedToolCall preparedCall(String toolId) {
        return new PreparedToolCall(toolId, Map.of(), executor);
    }
}
