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

package com.koawa.agent.agent.executor;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentObservation;
import com.koawa.agent.agent.domain.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutingAgentActionExecutorTest {

    @Test
    void shouldRouteActionToMatchingHandler() {
        AgentActionHandler handler = new AgentActionHandler() {
            @Override
            public AgentActionType supportedAction() {
                return AgentActionType.CALL_MCP_TOOL;
            }

            @Override
            public AgentObservation execute(AgentAction action, AgentState state) {
                return AgentObservation.builder()
                        .actionType(action.getType())
                        .content("tool result")
                        .success(true)
                        .build();
            }
        };

        RoutingAgentActionExecutor executor = new RoutingAgentActionExecutor(List.of(handler));

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .thought("call tool")
                .build();

        AgentObservation result = executor.execute(
                action,
                AgentState.builder().build()
        );

        assertTrue(result.isSuccess());
        assertEquals("tool result", result.getContent());
    }

    @Test
    void shouldRejectActionWithoutHandler() {
        RoutingAgentActionExecutor executor = new RoutingAgentActionExecutor(List.of());

        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .build();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(
                        action,
                        AgentState.builder().build()
                )
        );

        assertEquals(
                "No handler for action type: CALL_MCP_TOOL",
                exception.getMessage()
        );
    }
}
