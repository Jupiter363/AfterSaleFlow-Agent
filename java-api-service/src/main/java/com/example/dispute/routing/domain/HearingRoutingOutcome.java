package com.example.dispute.routing.domain;

import java.util.Objects;

/** Immutable routing evidence persisted before a workflow path is started. */
public record HearingRoutingOutcome(
        HearingRoute route,
        String reasonCode,
        boolean terminalInDisputeSystem,
        boolean requiresAdditionalEvidence,
        boolean requiresDeliberation) {

    public HearingRoutingOutcome {
        Objects.requireNonNull(route, "route must not be null");
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (route == HearingRoute.TRANSFERRED && !terminalInDisputeSystem) {
            throw new IllegalArgumentException(
                    "TRANSFERRED must be terminal in the dispute system");
        }
    }
}
