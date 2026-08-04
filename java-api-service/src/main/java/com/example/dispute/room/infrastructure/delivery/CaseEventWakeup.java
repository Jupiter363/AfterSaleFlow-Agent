package com.example.dispute.room.infrastructure.delivery;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Best-effort Redis hint for a durable PostgreSQL case timeline cursor. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CaseEventWakeup(String schemaVersion, String caseId, long durableSequence) {

    public static final String SCHEMA_VERSION = "case-event-wakeup.v1";

    public CaseEventWakeup {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported wakeup schemaVersion");
        }
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (durableSequence < 0) {
            throw new IllegalArgumentException("durableSequence must not be negative");
        }
    }
}
