package com.example.dispute.routing.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Objects;

/**
 * Deterministic facts used to choose the hearing route.
 *
 * <p>Free-form model reasoning must be normalized into these fields before routing so the workflow
 * decision is replayable and auditable.
 */
public record AdmissibilityContext(
        boolean fulfillmentDispute,
        RiskLevel riskLevel,
        boolean evidenceSufficient,
        boolean conflictDetected,
        boolean ruleClear) {

    public AdmissibilityContext {
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }
}
