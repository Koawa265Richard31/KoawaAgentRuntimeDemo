package com.koawa.agent.agent.parser;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentActionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentActionParserTest {

    private final AgentActionParser parser = new AgentActionParser();

    @Test
    void shouldParseValidToolAction() {
        String raw = """
                {
                  "type": "CALL_MCP_TOOL",
                  "thought": "需要调用工具",
                  "arguments": {
                    "toolId": "knowledge.search",
                    "params": {"query": "员工请假流程"}
                  }
                }
                """;

        AgentAction action = parser.parse(raw);

        assertEquals(AgentActionType.CALL_MCP_TOOL, action.getType());
        assertEquals("需要调用工具", action.getThought());
        assertEquals("knowledge.search", action.getArguments().get("toolId"));
        assertFalse(action.getType().isTerminal());
    }

    @Test
    void shouldRejectBlankResponse() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("   ")
        );

        assertEquals(
                "Agent action response is empty",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectUnknownType() {
        String raw = """
                {
                  "type": "NULL",
                  "thought": "需要查询知识库",
                  "arguments": {
                    "query": "员工请假流程",
                    "topK": 5
                  }
                }
                """;
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(raw)
        );

        assertEquals(
                "Unknown agent action type: NULL",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectMalformedJson() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("{Invalid json}")
        );

        assertEquals(
                "Agent action is invalid JSON",
                exception.getMessage()
        );
    }

    @Test
    void shouldParseMarkdownCodeFence() {
        String raw = """
            ```json
            {
              "type": "FINAL_ANSWER",
              "thought": "信息已经足够",
              "arguments": {}
            }
            ```
            """;

        AgentAction action = parser.parse(raw);

        assertEquals(AgentActionType.FINAL_ANSWER, action.getType());
        assertTrue(action.getType().isTerminal());
    }
}
