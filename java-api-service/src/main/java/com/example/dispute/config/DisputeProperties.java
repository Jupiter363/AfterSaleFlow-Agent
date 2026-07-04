package com.example.dispute.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "dispute")
public record DisputeProperties(
        @DefaultValue("PT2H") Duration evidenceWindow,
        @DefaultValue("PT3H") Duration hearingWindow,
        @DefaultValue("PT5M") Duration hearingRoundWindow,
        @DefaultValue("3") int maxHearingRounds,
        @DefaultValue("PT15S") Duration sseHeartbeat,
        @DefaultValue("true") boolean seedDemoDisputes) {

    public DisputeProperties {
        requirePositive(evidenceWindow, "evidence-window");
        requirePositive(hearingWindow, "hearing-window");
        requirePositive(hearingRoundWindow, "hearing-round-window");
        requirePositive(sseHeartbeat, "sse-heartbeat");
        if (maxHearingRounds < 1 || maxHearingRounds > 5) {
            throw new IllegalArgumentException(
                    "max-hearing-rounds must be between 1 and 5");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}
