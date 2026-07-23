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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultAgentRecoveryPolicyTest {

    private final AgentRecoveryPolicy policy =
            new DefaultAgentRecoveryPolicy();

    @Test
    void shouldMapFailureTypesToRecoveryDecisions() {
        assertAll(
                () -> assertEquals(
                        AgentRecoveryDecision.RETRY_PLANNING,
                        policy.decide(AgentFailureType.EMPTY_MODEL_RESPONSE)
                ),
                () -> assertEquals(
                        AgentRecoveryDecision.RETRY_PLANNING,
                        policy.decide(AgentFailureType.INVALID_ACTION_RESPONSE)
                ),
                () -> assertEquals(
                        AgentRecoveryDecision.STOP,
                        policy.decide(AgentFailureType.MODEL_CALL_FAILED)
                ),
                () -> assertEquals(
                        AgentRecoveryDecision.STOP,
                        policy.decide(AgentFailureType.ACTION_EXECUTION_FAILED)
                ),
                () -> assertEquals(
                        AgentRecoveryDecision.STOP,
                        policy.decide(AgentFailureType.UNEXPECTED)
                )
        );
    }
}
