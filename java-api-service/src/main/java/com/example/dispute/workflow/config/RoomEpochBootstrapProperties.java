package com.example.dispute.workflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.orchestration.room-epoch-bootstrap")
public record RoomEpochBootstrapProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("32") int batchSize,
        @DefaultValue("32") int concurrency,
        @DefaultValue("PT2M") Duration leaseDuration,
        @DefaultValue("PT90S") Duration completionTimeout,
        @DefaultValue("PT1S") Duration baseBackoff,
        @DefaultValue("PT5M") Duration maxBackoff,
        @DefaultValue("PT5S") Duration pollInterval) {

    public RoomEpochBootstrapProperties {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        if (concurrency < 1 || concurrency > batchSize) {
            throw new IllegalArgumentException("concurrency must be between 1 and batchSize");
        }
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(completionTimeout, "completionTimeout");
        requirePositive(baseBackoff, "baseBackoff");
        requirePositive(maxBackoff, "maxBackoff");
        requirePositive(pollInterval, "pollInterval");
        if (baseBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("baseBackoff must not exceed maxBackoff");
        }
        if (completionTimeout.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("completionTimeout must be shorter than leaseDuration");
        }
    }

    private static void requirePositive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
