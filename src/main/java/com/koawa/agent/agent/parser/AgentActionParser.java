package com.koawa.agent.agent.parser;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import com.koawa.agent.infra.util.LLMResponseCleaner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentActionParser {

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentAction parse(String raw) {

        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Agent action response is empty");
        }

        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Agent action is invalid JSON", e);
        }

        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Agent action must be a json object");
        }

        // type
        JsonNode typeNode = root.get("type");
        if (typeNode == null || typeNode.isNull()) {
            throw new IllegalArgumentException("Agent action type is missing");
        }

        AgentActionType type;
        try {
            type = AgentActionType.valueOf(
                    typeNode.asText().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown agent action type: " + typeNode.asText(),
                    e
            );
        }

        // thought
        JsonNode thoughtNode = root.get("thought");
        String thought = thoughtNode != null && !thoughtNode.isNull()
                ? thoughtNode.asText()
                : "";

        // arguments
        Map<String, Object> arguments = new HashMap<>();
        JsonNode argumentsNode = root.get("arguments");
        if (argumentsNode != null && argumentsNode.isObject()) {
            arguments = objectMapper.convertValue(
                    argumentsNode,
                    STRING_OBJECT_MAP
            );
        }

        return AgentAction.builder()
                .type(type)
                .thought(thought)
                .arguments(arguments)
                .build();
    }
}
