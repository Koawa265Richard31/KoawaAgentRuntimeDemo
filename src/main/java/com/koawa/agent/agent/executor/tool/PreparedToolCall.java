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

package com.koawa.agent.agent.executor.tool;

import com.koawa.agent.agent.mcp.McpToolExecutor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PreparedToolCall(
        String toolId,
        Map<String, Object> parameters,
        McpToolExecutor executor
) {
    public PreparedToolCall {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException(
                    "toolId must be a non-blank string"
            );
        }

        toolId = toolId.trim();

        parameters = Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Objects.requireNonNull(
                                parameters,
                                "parameters cannot be null"
                        )
                )
        );
        Objects.requireNonNull(executor, "executor cannot be null");
    }
}
