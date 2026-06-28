package com.example.dispute.router.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Objects;

public record RoutingContext(
        String caseType,
        String disputeType,
        RiskLevel riskLevel,
        boolean evidenceSufficient,
        boolean conflictDetected,
        boolean policyMatched) {

    public RoutingContext {
        if (caseType == null || caseType.isBlank()) {
            throw new IllegalArgumentException("caseType must not be blank");
        }
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }
}
