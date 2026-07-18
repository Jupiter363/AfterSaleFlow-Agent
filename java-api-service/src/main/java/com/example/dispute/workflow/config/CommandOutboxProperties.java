package com.example.dispute.workflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.orchestration.command-outbox")
public record CommandOutboxProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("32") int batchSize,
        @DefaultValue("PT1M") Duration leaseDuration,
        @DefaultValue("PT1S") Duration baseBackoff,
        @DefaultValue("PT5M") Duration maxBackoff,
        @DefaultValue("PT5S") Duration pollInterval) {

    public CommandOutboxProperties {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(baseBackoff, "baseBackoff");
        requirePositive(maxBackoff, "maxBackoff");
        requirePositive(pollInterval, "pollInterval");
        if (baseBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("baseBackoff must not exceed maxBackoff");
        }
    }

    private static void requirePositive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
