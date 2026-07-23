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
