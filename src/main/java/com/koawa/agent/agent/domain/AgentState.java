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

import com.koawa.agent.framework.convention.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentState {

    private String conversationId;

    private String taskId;

    private String userId;

    private String originalQuestion;

    private int currentStep;

    private int maxSteps;

    private Instant deadlineAt;

    @Builder.Default
    private List<AgentStep> steps = new ArrayList<>();

    @Builder.Default
    private List<ChatMessage> historySnapshot = List.of();

    private String finalAnswer;

    private AgentStopReason stopReason;

    private AgentFailureType failureType;

    private String errorMessage;

    private int planningRecoveryAttempts;

    private Integer consumedUserInputStep;

}
