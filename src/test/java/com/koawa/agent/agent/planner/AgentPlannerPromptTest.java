package com.koawa.agent.agent.planner;

import com.koawa.agent.agent.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentPlannerPromptTest {

    @Test
    void shouldLoadAndRenderPlannerPrompt() {
        PromptTemplateLoader loader = new PromptTemplateLoader(
                new DefaultResourceLoader()
        );

        String rendered = loader.render(
                "prompt/agent-planner.st",
                Map.of(
                        "original_question", "上海天气怎么样？",
                        "current_step", "1",
                        "max_steps", "3",
                        "steps", "Step 0: CALL_MCP_TOOL",
                        "tools", "toolId: weather"
                )
        );

        assertAll(
                () -> assertTrue(rendered.contains("上海天气怎么样？")),
                () -> assertTrue(rendered.contains("Step 0: CALL_MCP_TOOL")),
                () -> assertTrue(rendered.contains("toolId: weather")),
                () -> assertTrue(rendered.contains("CALL_MCP_TOOL")),
                () -> assertTrue(rendered.contains("ASK_CLARIFICATION")),
                () -> assertTrue(rendered.contains("FINAL_ANSWER")),
                () -> assertFalse(rendered.contains("{original_question}")),
                () -> assertFalse(rendered.contains("{current_step}")),
                () -> assertFalse(rendered.contains("{max_steps}")),
                () -> assertFalse(rendered.contains("{steps}")),
                () -> assertFalse(rendered.contains("{tools}")),
                () -> assertFalse(rendered.contains("\"finish\""))
        );
    }
}
