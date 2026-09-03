package com.example.dispute.workflow.config;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.orchestration")
public record OrchestrationCutoverProperties(
        @DefaultValue("LEGACY") WriterMode newEpochMode,
        @DefaultValue("false") boolean nonLegacyEpochAllocationEnabled,
        @DefaultValue("false") boolean temporalWriterEnabled) {

    public OrchestrationCutoverProperties {
        if (newEpochMode == null) {
            throw new IllegalArgumentException("newEpochMode must be configured");
        }
        if (temporalWriterEnabled && !nonLegacyEpochAllocationEnabled) {
            throw new IllegalArgumentException(
                    "temporalWriterEnabled requires nonLegacyEpochAllocationEnabled");
        }
    }
}
