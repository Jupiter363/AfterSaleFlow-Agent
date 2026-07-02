package com.example.dispute.routing.domain;

import com.example.dispute.domain.model.RiskLevel;

/**
 * Selects a final hearing path without adjudicating liability or planning an action.
 *
 * <p>The router deliberately has no repository, Agent, approval, or execution dependency. Keeping
 * this decision pure prevents an intake recommendation from becoming a hidden final decision.
 */
public final class AdmissibilityHearingRouter {

    public HearingRoutingOutcome decide(AdmissibilityContext context) {
        if (!context.fulfillmentDispute()) {
            return new HearingRoutingOutcome(
                    HearingRoute.TRANSFERRED,
                    "NOT_A_FULFILLMENT_DISPUTE",
                    true,
                    false,
                    false);
        }

        boolean highRisk =
                context.riskLevel() == RiskLevel.HIGH
                        || context.riskLevel() == RiskLevel.CRITICAL;
        if (highRisk
                || context.conflictDetected()
                || !context.evidenceSufficient()
                || !context.ruleClear()) {
            return new HearingRoutingOutcome(
                    HearingRoute.FULL_HEARING,
                    fullHearingReason(context, highRisk),
                    false,
                    !context.evidenceSufficient(),
                    highRisk);
        }

        return new HearingRoutingOutcome(
                HearingRoute.SIMPLE_HEARING,
                "CLEAR_LOW_RISK_DISPUTE",
                false,
                false,
                false);
    }

    private String fullHearingReason(
            AdmissibilityContext context, boolean highRisk) {
        if (highRisk) {
            return "HIGH_RISK_REQUIRES_FULL_HEARING";
        }
        if (context.conflictDetected()) {
            return "PARTY_FACTS_CONFLICT";
        }
        if (!context.evidenceSufficient()) {
            return "KEY_EVIDENCE_INSUFFICIENT";
        }
        return "RULE_APPLICATION_UNCLEAR";
    }
}
