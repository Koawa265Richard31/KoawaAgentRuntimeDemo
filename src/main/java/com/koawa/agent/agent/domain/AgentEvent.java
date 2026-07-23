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

import java.util.Objects;

public record AgentEvent(
        AgentEventType type,
        String conversationId,
        String taskId,
        Integer stepIndex,
        AgentActionType actionType,
        Boolean success,
        AgentStopReason stopReason,
        String content,
        String errorMessage
) {

    public AgentEvent {
        Objects.requireNonNull(type, "type cannot be null");
    }

    public static AgentEvent turnStarted(
            String conversationId,
            String taskId,
            int stepIndex
    ) {
        return new AgentEvent(
                AgentEventType.TURN_STARTED,
                conversationId,
                taskId,
                stepIndex,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static AgentEvent stepStarted(
            String conversationId,
            String taskId,
            int stepIndex
    ) {
        return new AgentEvent(
                AgentEventType.STEP_STARTED,
                conversationId,
                taskId,
                stepIndex,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static AgentEvent actionPlanned(
            String conversationId,
            String taskId,
            int stepIndex,
            AgentActionType actionType
    ) {
        return new AgentEvent(
                AgentEventType.ACTION_PLANNED,
                conversationId,
                taskId,
                stepIndex,
                Objects.requireNonNull(
                        actionType,
                        "actionType cannot be null"
                ),
                null,
                null,
                null,
                null
        );
    }

    public static AgentEvent observationReceived(
            String conversationId,
            String taskId,
            int stepIndex,
            AgentActionType actionType,
            boolean success,
            String content,
            String errorMessage
    ) {
        return new AgentEvent(
                AgentEventType.OBSERVATION_RECEIVED,
                conversationId,
                taskId,
                stepIndex,
                Objects.requireNonNull(
                        actionType,
                        "actionType cannot be null"
                ),
                success,
                null,
                content,
                errorMessage
        );
    }

    public static AgentEvent turnCompleted(
            String conversationId,
            String taskId,
            AgentStopReason stopReason,
            String content
    ) {
        return new AgentEvent(
                AgentEventType.TURN_COMPLETED,
                conversationId,
                taskId,
                null,
                null,
                null,
                Objects.requireNonNull(
                        stopReason,
                        "stopReason cannot be null"
                ),
                content,
                null
        );
    }

    public static AgentEvent turnFailed(
            String conversationId,
            String taskId,
            String errorMessage
    ) {
        return new AgentEvent(
                AgentEventType.TURN_FAILED,
                conversationId,
                taskId,
                null,
                null,
                false,
                AgentStopReason.ERROR,
                null,
                errorMessage
        );
    }
}
