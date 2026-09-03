package com.example.dispute.agentstream.infrastructure.delivery;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Redis hint that tells an SSE node which PostgreSQL attempt cursor to catch up. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentRunStreamWakeup(
        String schemaVersion, String runId, String attemptId, long durableHighWatermark) {

    public static final String SCHEMA_VERSION = "agent-stream-wakeup.v1";

    public AgentRunStreamWakeup {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported wakeup schemaVersion");
        }
        required(runId, "runId");
        required(attemptId, "attemptId");
        if (durableHighWatermark < 0) {
            throw new IllegalArgumentException("durableHighWatermark must not be negative");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
