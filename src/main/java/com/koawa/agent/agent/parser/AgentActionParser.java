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

package com.koawa.agent.agent.parser;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.infra.util.LLMResponseCleaner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentActionParser {

    private final Gson gson = new Gson();

    public AgentAction parse(String raw) {

        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Agent action response is empty");
        }

        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        JsonElement root;
        try {
            root = JsonParser.parseString(cleaned);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Agent action is invalid JSON", e);
        }

        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Agent action must be a json object");
        }

        JsonObject json = root.getAsJsonObject();

        // type
        if (!json.has("type") || json.get("type").isJsonNull()) {
            throw new IllegalArgumentException("Agent action type is missing");
        }

        AgentActionType type;
        try {
            type = AgentActionType.valueOf(
                    json.get("type").getAsString().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown agent action type: " + json.get("type").getAsString(),
                    e
            );
        }

        // thought
        String thought = json.has("thought") && !json.get("thought").isJsonNull()
                ? json.get("thought").getAsString()
                : "";

        // arguments
        Map<String, Object> arguments = new HashMap<>();
        if (json.has("arguments") && json.get("arguments").isJsonObject()) {
            arguments = gson.fromJson(json.getAsJsonObject("arguments"), Map.class);
        }

        return AgentAction.builder()
                .type(type)
                .thought(thought)
                .arguments(arguments)
                .build();
    }
}
