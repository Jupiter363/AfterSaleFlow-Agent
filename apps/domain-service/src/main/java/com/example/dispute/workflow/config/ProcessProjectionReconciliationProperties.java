package com.example.dispute.workflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.orchestration.projection-reconciliation")
public record ProcessProjectionReconciliationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("32") int batchSize,
        @DefaultValue("PT5M") Duration claimDuration,
        @DefaultValue("PT30S") Duration pollInterval) {

    public ProcessProjectionReconciliationProperties {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        requirePositive(claimDuration, "claimDuration");
        requirePositive(pollInterval, "pollInterval");
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
