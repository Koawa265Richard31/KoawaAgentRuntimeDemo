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

package com.koawa.agent.agent.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRunResultTest {

    @Test
    void shouldCreateRunSummaryFromState() {
        AgentState state = AgentState.builder()
                .conversationId("conversation-1")
                .taskId("task-1")
                .stopReason(AgentStopReason.ERROR)
                .steps(List.of(
                        AgentStep.builder().stepIndex(0).build(),
                        AgentStep.builder().stepIndex(1).build()
                ))
                .planningRecoveryAttempts(1)
                .failureType(AgentFailureType.MODEL_CALL_FAILED)
                .errorMessage("model unavailable")
                .build();

        AgentRunResult result = AgentRunResult.from(state);

        assertEquals("conversation-1", result.conversationId());
        assertEquals("task-1", result.taskId());
        assertEquals(AgentStopReason.ERROR, result.stopReason());
        assertEquals(2, result.stepCount());
        assertEquals(1, result.planningRecoveryAttempts());
        assertEquals(
                AgentFailureType.MODEL_CALL_FAILED,
                result.failureType()
        );
        assertNull(result.content());
        assertEquals("model unavailable", result.errorMessage());
    }

    @Test
    void shouldRejectNullState() {
        assertThrows(
                NullPointerException.class,
                () -> AgentRunResult.from(null)
        );
    }
}
