package com.koawa.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "agent.llm.api-key=test-key"
        }
)
class KoawaAgentApplicationTest {

    @Test
    void contextLoads() {
    }
}
