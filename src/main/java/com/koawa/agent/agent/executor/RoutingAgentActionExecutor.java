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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RoutingAgentActionExecutor implements AgentActionExecutor {

    private final Map<AgentActionType, AgentActionHandler> handlers;

    public RoutingAgentActionExecutor(List<AgentActionHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers cannot be null");

        this.handlers = new EnumMap<>(AgentActionType.class);

        for (AgentActionHandler handler : handlers) {
            Objects.requireNonNull(handler,"handler cannot be null");

            AgentActionHandler previous = this.handlers.put(
                    handler.supportedAction(),
                    handler
            );

            if(previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate handler for action type: "
                                + handler.supportedAction()
                );
            }
        }
    }


    @Override
    public AgentObservation execute(AgentAction action, AgentState state) {
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(action.getType(), "action type cannot be null");
        Objects.requireNonNull(state, "state cannot be null");

        AgentActionHandler handler = handlers.get(action.getType());

        if(handler == null) {
            throw new IllegalStateException(
                    "No handler for action type: " + action.getType()
            );
        }
        return handler.execute(action, state);
    }
}
