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

package com.koawa.agent.agent.event;

import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.agent.domain.AgentEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingAgentEventSinkTest {

    private final LoggingAgentEventSink sink =
            new LoggingAgentEventSink();

    @Test
    void shouldLogMetadataWithoutRawContent(CapturedOutput output) {
        sink.publish(AgentEvent.observationReceived(
                "conversation-1",
                "task-1",
                0,
                AgentActionType.CALL_MCP_TOOL,
                false,
                "sensitive-content",
                "sensitive-error"
        ));

        assertThat(output)
                .contains("agent_event")
                .contains("type=OBSERVATION_RECEIVED")
                .contains("conversationId=conversation-1")
                .contains("taskId=task-1")
                .contains("stepIndex=0")
                .contains("actionType=CALL_MCP_TOOL")
                .contains("success=false")
                .contains("hasContent=true")
                .contains("hasError=true")
                .doesNotContain(
                        "sensitive-content",
                        "sensitive-error"
                );
    }
}
