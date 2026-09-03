package com.example.dispute.workflow.application.epoch;

import java.util.Objects;

/** Java-owned identity and traffic classification used when selecting a new room epoch. */
public record RoomEpochSelectionContext(
        String tenantSurrogate, String caseId, TrafficSource trafficSource) {

    public RoomEpochSelectionContext {
        requireText(tenantSurrogate, "tenantSurrogate");
        requireText(caseId, "caseId");
        Objects.requireNonNull(trafficSource, "trafficSource must not be null");
    }

    public static RoomEpochSelectionContext realCase(String tenantSurrogate, String caseId) {
        return new RoomEpochSelectionContext(
                tenantSurrogate, caseId, TrafficSource.AUTHENTICATED_REAL_CASE);
    }

    public static RoomEpochSelectionContext verifiedSignedSynthetic(
            String tenantSurrogate, String caseId) {
        return new RoomEpochSelectionContext(
                tenantSurrogate, caseId, TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC);
    }

    public enum TrafficSource {
        AUTHENTICATED_REAL_CASE,
        AUTHENTICATED_SIGNED_SYNTHETIC
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
