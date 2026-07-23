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
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AskClarificationActionHandlerTest {

    @Test
    void shouldBuildClarificationObservationFromQuestion() {
        AgentAction action = AgentAction.builder()
                .type(AgentActionType.ASK_CLARIFICATION)
                .arguments(Map.of(
                        "question", "  请提供区域和月份  "
                ))
                .build();

        AgentState state = AgentState.builder().build();

        AgentObservation result = new AskClarificationActionHandler()
                .execute(action,state);

        assertTrue(result.isSuccess());

        assertEquals(AgentActionType.ASK_CLARIFICATION,result.getActionType());
        assertEquals("请提供区域和月份", result.getContent());
        assertNull(result.getErrorMessage());
        assertNull(state.getStopReason());
        assertNull(state.getFinalAnswer());
    }

    @Test
    void shouldRejectBlankQuestion() {

        AgentAction action = AgentAction.builder()
                .arguments(Map.of(
                        "question", "   "
                ))
                .type(AgentActionType.ASK_CLARIFICATION)
                .build();

        AgentState state = AgentState.builder().build();

        AskClarificationActionHandler handler = new AskClarificationActionHandler();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> handler.execute(action, state)
        );

        assertEquals("ASK_CLARIFICATION question must be a non-blank string", exception.getMessage());
    }


}
