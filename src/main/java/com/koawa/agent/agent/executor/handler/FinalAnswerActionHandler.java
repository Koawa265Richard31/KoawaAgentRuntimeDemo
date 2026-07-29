package com.koawa.agent.agent.executor.handler;

import com.koawa.agent.agent.domain.*;
import com.koawa.agent.agent.executor.AgentActionHandler;
import com.koawa.agent.framework.convention.ChatMessage;
import com.koawa.agent.framework.convention.ChatRequest;
import com.koawa.agent.infra.chat.LLMService;
import com.koawa.agent.agent.prompt.PromptTemplateLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FinalAnswerActionHandler implements AgentActionHandler {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    public FinalAnswerActionHandler(LLMService llmService,
                                    PromptTemplateLoader promptTemplateLoader
    ) {
        this.llmService = Objects.requireNonNull(
                llmService,
                "llmService cannot be null"
        );
        this.promptTemplateLoader = Objects.requireNonNull(
                promptTemplateLoader,
                "promptTemplateLoader cannot be null"
        );
    }

    @Override
    public AgentActionType supportedAction() {
        return AgentActionType.FINAL_ANSWER;
    }

    @Override
    public AgentObservation execute(AgentAction action, AgentState state) {
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(state, "state cannot be null");

        if (action.getType() != AgentActionType.FINAL_ANSWER) {
            throw new IllegalArgumentException(
                    "Unsupported action type: " + action.getType()
            );
        }

        if (state.getOriginalQuestion() == null || state.getOriginalQuestion().isBlank()) {
            throw new IllegalArgumentException(
                    "Original question must be a non-blank string"
            );
        }

        Map<String, String> slots = Map.of(
                "original_question", state.getOriginalQuestion().trim(),
                "observations", formatObservations(state)
        );

        String prompt = promptTemplateLoader.render("prompt/agent-final-answer.st", slots);

        ChatRequest request = ChatRequest.builder()
                .messages(buildMessages(state, prompt))
                .deadlineAt(state.getDeadlineAt())
                .build();

        String answer = llmService.chat(request);

        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException(
                    "FINAL_ANSWER LLM returned a blank answer"
            );
        }

        return AgentObservation.builder()
                .actionType(AgentActionType.FINAL_ANSWER)
                .content(answer.trim())
                .success(true)
                .build();
    }

    private String formatObservations(AgentState state) {
        List<AgentStep> steps = state.getSteps();

        if (steps == null || steps.isEmpty()) {
            return "无历史 Observation";
        }

        StringBuilder builder = new StringBuilder();

        for (AgentStep step : steps) {
            AgentObservation observation = Objects.requireNonNull(
                    step.getObservation(),
                    "step observation cannot be null"
            );

            builder.append("Step ")
                    .append(step.getStepIndex())
                    .append('\n');

            builder.append("actionType: ")
                    .append(observation.getActionType())
                    .append('\n');

            builder.append("success: ")
                    .append(observation.isSuccess())
                    .append('\n');

            builder.append("content: ")
                    .append(formatNullable(observation.getContent()))
                    .append('\n');

            builder.append("errorMessage: ")
                    .append(formatNullable(observation.getErrorMessage()))
                    .append("\n\n");
        }

        return builder.toString().trim();
    }

    private String formatNullable(String value) {
        return value == null || value.isBlank()
                ? "无"
                : value.trim();
    }

    private List<ChatMessage> buildMessages(
            AgentState state,
            String prompt
    ) {
        List<ChatMessage> messages = new ArrayList<>();

        if (state.getHistorySnapshot() != null) {
            messages.addAll(state.getHistorySnapshot());
        }

        messages.add(ChatMessage.user(prompt));
        return List.copyOf(messages);
    }
}
