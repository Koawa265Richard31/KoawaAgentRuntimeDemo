package com.koawa.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "agent.llm.api-key=test-key",
                "spring.datasource.url=jdbc:h2:mem:application-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver"
        }
)
class KoawaAgentApplicationTest {

    @Test
    void contextLoads() {
    }
}
