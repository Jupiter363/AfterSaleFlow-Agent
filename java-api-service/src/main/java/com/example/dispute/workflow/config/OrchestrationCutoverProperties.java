package com.example.dispute.workflow.config;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.orchestration")
public record OrchestrationCutoverProperties(
        @DefaultValue("LEGACY") WriterMode newEpochMode) {

    public OrchestrationCutoverProperties {
        if (newEpochMode == null) {
            throw new IllegalArgumentException("newEpochMode must be configured");
        }
    }
}
