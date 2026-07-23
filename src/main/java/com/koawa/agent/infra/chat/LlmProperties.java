package com.koawa.agent.infra.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Data
@Validated
@ConfigurationProperties(prefix = "agent.llm")
public class LlmProperties {

    @NotBlank
    private String baseUrl = "https://api.siliconflow.cn/v1";

    private String apiKey = "";

    @NotBlank
    private String model = "Pro/zai-org/GLM-4.7";

    @NotNull
    @DurationMin(millis = 1)
    private Duration requestTimeout = Duration.ofSeconds(60);
}
