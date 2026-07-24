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

package com.koawa.agent.agent.service.impl;

import com.koawa.agent.agent.config.AgentRuntimeProperties;
import com.koawa.agent.agent.domain.AgentRunResult;
import com.koawa.agent.agent.domain.AgentState;
import com.koawa.agent.agent.runner.AgentCheckpointLifecycle;
import com.koawa.agent.agent.runner.AgentLoopRunner;
import com.koawa.agent.agent.service.AgentChatService;
import com.koawa.agent.agent.service.AgentConversationHistoryLoader;
import com.koawa.agent.framework.convention.ChatMessage;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DefaultAgentChatService implements AgentChatService {

    private final AgentLoopRunner runner;
    private final AgentRuntimeProperties properties;
    private final AgentConversationHistoryLoader historyLoader;
    private final Clock clock;
    private final AgentCheckpointLifecycle checkpointLifecycle;

    public DefaultAgentChatService(
            AgentLoopRunner runner,
            AgentRuntimeProperties properties,
            AgentConversationHistoryLoader historyLoader
    ) {
        this(
                runner,
                properties,
                historyLoader,
                Clock.systemUTC(),
                AgentCheckpointLifecycle.NOOP
        );
    }

    public DefaultAgentChatService(
            AgentLoopRunner runner,
            AgentRuntimeProperties properties,
            AgentConversationHistoryLoader historyLoader,
            Clock clock
    ) {
        this(
                runner,
                properties,
                historyLoader,
                clock,
                AgentCheckpointLifecycle.NOOP
        );
    }

    public DefaultAgentChatService(
            AgentLoopRunner runner,
            AgentRuntimeProperties properties,
            AgentConversationHistoryLoader historyLoader,
            Clock clock,
            AgentCheckpointLifecycle checkpointLifecycle
    ) {
        this.runner = Objects.requireNonNull(
                runner,
                "runner cannot be null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties cannot be null"
        );
        this.historyLoader = Objects.requireNonNull(
                historyLoader,
                "historyLoader cannot be null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock cannot be null"
        );
        this.checkpointLifecycle = Objects.requireNonNull(
                checkpointLifecycle,
                "checkpointLifecycle cannot be null"
        );
    }

    @Override
    public AgentRunResult chat(
            String question,
            String conversationId,
            String taskId,
            String userId
    ) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException(
                    "question cannot be blank"
            );
        }

        String actualConversationId = resolveId(conversationId);
        String actualTaskId = resolveId(taskId);
        Instant deadlineAt = clock.instant().plus(
                properties.getTurnTimeout()
        );

        List<ChatMessage> loadedHistory =
                historyLoader.load(actualConversationId, userId);

        List<ChatMessage> historySnapshot =
                loadedHistory == null
                        ? List.of()
                        : List.copyOf(loadedHistory);

        AgentState initialState = AgentState.builder()
                .conversationId(actualConversationId)
                .taskId(actualTaskId)
                .userId(userId)
                .originalQuestion(question)
                .currentStep(0)
                .maxSteps(properties.getMaxSteps())
                .deadlineAt(deadlineAt)
                .historySnapshot(historySnapshot)
                .build();

        checkpointLifecycle.initialize(initialState);

        AgentState completedState = Objects.requireNonNull(
                runner.run(initialState),
                "runner returned null state"
        );

        checkpointLifecycle.completed(completedState);

        return AgentRunResult.from(completedState);
    }

    private String resolveId(String id) {
        if (id == null || id.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return id.trim();
    }
}
