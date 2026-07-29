package com.koawa.agent.agent.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Validated
@ConfigurationProperties(prefix = "agent.runtime")
public class AgentRuntimeProperties {
    @Min(1)
    private int maxSteps = 8;

    @NotNull
    @DurationMin(millis = 1)
    private Duration turnTimeout = Duration.ofSeconds(120);

    /**
     * Agent 可以调用的 MCP 工具。
     * 空集合表示全部拒绝。
     */
    private Set<String> allowedToolIds = new LinkedHashSet<>();

}
