package com.koawa.agent.agent.checkpoint.lease;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Operational timing configuration for resumed-task execution leases.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "agent.checkpoint.execution")
public class AgentExecutionLeaseProperties {

    @NotNull
    @DurationMin(millis = 1)
    private Duration leaseDuration = Duration.ofSeconds(30);

    @NotNull
    @DurationMin(millis = 1)
    private Duration renewInterval = Duration.ofSeconds(10);

    @AssertTrue(
            message = "renewInterval must be shorter than leaseDuration"
    )
    public boolean isRenewIntervalShorterThanLeaseDuration() {
        return leaseDuration != null
                && renewInterval != null
                && renewInterval.compareTo(leaseDuration) < 0;
    }
}
