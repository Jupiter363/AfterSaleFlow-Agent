package com.example.dispute.workflow.config;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.agent-run-v2")
public record AgentRunV2Properties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("V1") AgentRunProtocol protocolDefault,
        @DefaultValue("EXECUTOR") SchedulerMode schedulerMode,
        @DefaultValue("PT10M") Duration startToCloseTimeout,
        @DefaultValue("PT15S") Duration heartbeatTimeout,
        @DefaultValue("PT5S") Duration progressHeartbeatInterval) {

    public AgentRunV2Properties {
        if (protocolDefault == null || schedulerMode == null) {
            throw new IllegalArgumentException("protocolDefault and schedulerMode are required");
        }
        if (!enabled && protocolDefault != AgentRunProtocol.V1) {
            throw new IllegalArgumentException(
                    "versioned protocol default requires agent-run-v2.enabled=true");
        }
        if (enabled && schedulerMode == SchedulerMode.EXECUTOR) {
            throw new IllegalArgumentException(
                    "enabled AgentRun V2 cannot use the legacy scheduler EXECUTOR mode");
        }
        if (!Duration.ofMinutes(10).equals(startToCloseTimeout)
                || !Duration.ofSeconds(15).equals(heartbeatTimeout)
                || !Duration.ofSeconds(5).equals(progressHeartbeatInterval)) {
            throw new IllegalArgumentException("Phase 2 Activity timeouts are contract-fixed");
        }
    }

    public enum SchedulerMode {
        EXECUTOR,
        DETECTOR,
        OFF
    }
}
