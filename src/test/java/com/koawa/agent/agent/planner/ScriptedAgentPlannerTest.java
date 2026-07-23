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
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptedAgentPlannerTest {

    @Test
    void shouldReturnActionForCurrentStep() {
        AgentAction toolAction = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .build();

        AgentAction finalAction = AgentAction.builder()
                .type(AgentActionType.FINAL_ANSWER)
                .build();

        ScriptedAgentPlanner scriptedAgentPlanner = new ScriptedAgentPlanner(
                List.of(toolAction, finalAction)
        );

        AgentState agentState = AgentState.builder()
                .currentStep(0)
                .build();

        assertSame(toolAction, scriptedAgentPlanner.plan(agentState));

        agentState.setCurrentStep(1);

        assertSame(finalAction, scriptedAgentPlanner.plan(agentState));
    }

    @Test
    void shouldNotModifyCurrentStep() {
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .build();

        ScriptedAgentPlanner planner = new ScriptedAgentPlanner(List.of(action));

        AgentState state = AgentState.builder()
                .currentStep(0)
                .build();

        planner.plan(state);

        assertEquals(0, state.getCurrentStep());
    }

    @Test
    void shouldRejectStepBeyondScript() {
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.CALL_MCP_TOOL)
                .build();

        AgentState state = AgentState.builder()
                .currentStep(1)
                .build();

        ScriptedAgentPlanner planner = new ScriptedAgentPlanner(List.of(action));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> planner.plan(state)
        );

        assertEquals(
                "No scripted actions for step: 1",
                exception.getMessage()
        );
    }
}
