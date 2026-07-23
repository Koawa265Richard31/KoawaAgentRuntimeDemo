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
import com.koawa.agent.agent.executor.AgentActionHandler;

import java.util.Map;
import java.util.Objects;

public class AskClarificationActionHandler implements AgentActionHandler {

    @Override
    public AgentActionType supportedAction() {
        return AgentActionType.ASK_CLARIFICATION;
    }

    @Override
    public AgentObservation execute(AgentAction action, AgentState state) {

        Objects.requireNonNull(action);
        Objects.requireNonNull(state);

        if (action.getType() != AgentActionType.ASK_CLARIFICATION) {
            throw new IllegalArgumentException(
                    "Unsupported action type: " + action.getType()
            );
        }

        String question = resolveQuestion(action);

        return AgentObservation.builder()
                .actionType(AgentActionType.ASK_CLARIFICATION)
                .content(question)
                .success(true)
                .build();
    }

    private String resolveQuestion(AgentAction action) {
        Map<String, Object> arguments = action.getArguments();

        if(arguments == null) {
            throw new IllegalArgumentException(
                    "ASK_CLARIFICATION arguments cannot be null"
            );
        }

        Object questionValue = arguments.get("question");

        if(!(questionValue instanceof String question) || question.isBlank()) {
            throw new IllegalArgumentException(
                    "ASK_CLARIFICATION question must be a non-blank string"
            );
        }

        return question.trim();
    }
}
