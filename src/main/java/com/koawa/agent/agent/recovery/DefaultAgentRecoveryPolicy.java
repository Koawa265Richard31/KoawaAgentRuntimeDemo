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

package com.koawa.agent.agent.recovery;

import com.koawa.agent.agent.domain.AgentFailureType;

import java.util.Objects;

public final class DefaultAgentRecoveryPolicy
        implements AgentRecoveryPolicy {

    @Override
    public AgentRecoveryDecision decide(
            AgentFailureType failureType
    ) {
        Objects.requireNonNull(
                failureType,
                "failureType cannot be null"
        );

        return switch (failureType) {
            case EMPTY_MODEL_RESPONSE,
                 INVALID_ACTION_RESPONSE ->
                    AgentRecoveryDecision.RETRY_PLANNING;

            case UNEXPECTED,
                 MODEL_CALL_FAILED,
                 ACTION_EXECUTION_FAILED ->
                    AgentRecoveryDecision.STOP;
        };
    }
}
