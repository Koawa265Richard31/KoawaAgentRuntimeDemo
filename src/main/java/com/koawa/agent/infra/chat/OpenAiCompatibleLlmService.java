package com.koawa.agent.infra.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koawa.agent.framework.convention.ChatMessage;
import com.koawa.agent.framework.convention.ChatRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@EnableConfigurationProperties(LlmProperties.class)
public final class OpenAiCompatibleLlmService implements LLMService {

    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);

    private final LlmProperties properties;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    @Autowired
    public OpenAiCompatibleLlmService(LlmProperties properties) {
        this(
                properties,
                HttpClient.newBuilder()
                        .connectTimeout(properties.getRequestTimeout())
                        .build()
        );
    }

    OpenAiCompatibleLlmService(
            LlmProperties properties,
            HttpClient httpClient
    ) {
        this.properties = Objects.requireNonNull(properties);
        this.httpClient = Objects.requireNonNull(httpClient);
    }

    @Override
    public String chat(ChatRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        if (properties.getApiKey() == null
                || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "agent.llm.api-key is blank; set SILICONFLOW_API_KEY"
            );
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(resolveEndpoint())
                .timeout(resolveTimeout(request))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(request)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "LLM request failed with HTTP " + response.statusCode()
                );
            }
            return extractContent(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("LLM request failed", exception);
        }
    }

    private URI resolveEndpoint() {
        String baseUrl = properties.getBaseUrl().trim();
        if (baseUrl.endsWith("/chat/completions")) {
            return URI.create(baseUrl);
        }
        return URI.create(
                (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/")
                        + "chat/completions"
        );
    }

    private Duration resolveTimeout(ChatRequest request) {
        Duration configured = properties.getRequestTimeout();
        Instant deadlineAt = request.getDeadlineAt();
        if (deadlineAt == null) {
            return configured;
        }
        Duration remaining = Duration.between(Instant.now(), deadlineAt);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new IllegalStateException("LLM request deadline exceeded");
        }
        Duration effective = remaining.compareTo(configured) < 0
                ? remaining
                : configured;
        return effective.compareTo(MIN_TIMEOUT) < 0
                ? MIN_TIMEOUT
                : effective;
    }

    private String buildBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", properties.getModel());
        body.add("messages", toMessages(request.getMessages()));
        addIfPresent(body, "temperature", request.getTemperature());
        addIfPresent(body, "top_p", request.getTopP());
        addIfPresent(body, "top_k", request.getTopK());
        addIfPresent(body, "max_tokens", request.getMaxTokens());
        if (Boolean.TRUE.equals(request.getThinking())) {
            body.addProperty("enable_thinking", true);
        }
        return gson.toJson(body);
    }

    private JsonArray toMessages(List<ChatMessage> messages) {
        JsonArray result = new JsonArray();
        if (messages == null) {
            return result;
        }
        for (ChatMessage message : messages) {
            JsonObject json = new JsonObject();
            json.addProperty(
                    "role",
                    message.getRole().name().toLowerCase()
            );
            json.addProperty("content", message.getContent());
            result.add(json);
        }
        return result;
    }

    private void addIfPresent(JsonObject body, String name, Number value) {
        if (value != null) {
            body.addProperty(name, value);
        }
    }

    private String extractContent(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("LLM response contains no choices");
        }
        JsonObject message = choices.get(0)
                .getAsJsonObject()
                .getAsJsonObject("message");
        if (message == null
                || !message.has("content")
                || message.get("content").isJsonNull()) {
            throw new IllegalStateException("LLM response contains no content");
        }
        return message.get("content").getAsString();
    }
}
