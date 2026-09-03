package com.example.dispute.workflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.orchestration.domain-event-recovery")
public record CaseDomainEventRecoveryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("32") int workflowBatchSize,
        @DefaultValue("64") int eventBatchSize,
        @DefaultValue("PT5M") Duration claimDuration,
        @DefaultValue("PT5S") Duration pollInterval) {

    public CaseDomainEventRecoveryProperties {
        requireRange(workflowBatchSize, "workflowBatchSize");
        if (eventBatchSize < 1 || eventBatchSize > 128) {
            throw new IllegalArgumentException("eventBatchSize must be between 1 and 128");
        }
        requirePositive(claimDuration, "claimDuration");
        requirePositive(pollInterval, "pollInterval");
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireRange(int value, String field) {
        if (value < 1 || value > 1_000) {
            throw new IllegalArgumentException(field + " must be between 1 and 1000");
        }
    }
}
