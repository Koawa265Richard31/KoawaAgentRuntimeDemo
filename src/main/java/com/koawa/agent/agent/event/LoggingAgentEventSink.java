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

import com.koawa.agent.agent.domain.AgentEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
public final class LoggingAgentEventSink implements AgentEventSink {
    @Override
    public void publish(AgentEvent event) {
        Objects.requireNonNull(
                event,
                "event cannot be null"
        );

        log.info(
                "agent_event type={}, conversationId={}, "
                        + "taskId={}, stepIndex={}, actionType={}, "
                        + "success={}, stopReason={}, "
                        + "hasContent={}, hasError={}",
                event.type(),
                event.conversationId(),
                event.taskId(),
                event.stepIndex(),
                event.actionType(),
                event.success(),
                event.stopReason(),
                hasText(event.content()),
                hasText(event.errorMessage())
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
