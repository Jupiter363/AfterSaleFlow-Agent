package com.example.dispute.router.domain;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.util.Set;

public final class DisputeRouter {

    private static final Set<String> REGULAR_CASE_TYPES =
            Set.of(
                    "REGULAR_FULFILLMENT",
                    "LOGISTICS_QUERY",
                    "DELIVERY_STATUS",
                    "DELIVERY_REMINDER");

    public RoutingOutcome decide(RoutingContext context) {
        if (context.riskLevel() == RiskLevel.HIGH
                || context.riskLevel() == RiskLevel.CRITICAL) {
            return hearing(
                    "HIGH_RISK_REQUIRES_HEARING", !context.evidenceSufficient());
        }
        if (context.conflictDetected()
                || (context.disputeType() != null && !context.disputeType().isBlank())) {
            return hearing("PARTY_STATEMENTS_CONFLICT", false);
        }
        if (REGULAR_CASE_TYPES.contains(context.caseType())) {
            return new RoutingOutcome(
                    RouteType.REGULAR_FULFILLMENT,
                    "ORDINARY_FULFILLMENT_REQUEST",
                    false);
        }
        if (context.policyMatched() && context.evidenceSufficient()) {
            return new RoutingOutcome(
                    RouteType.RULE_BASED_RESOLUTION,
                    "POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT",
                    false);
        }
        return hearing(
                context.evidenceSufficient()
                        ? "COMPLEX_CASE_REQUIRES_HEARING"
                        : "KEY_EVIDENCE_INSUFFICIENT",
                !context.evidenceSufficient());
    }

    private static RoutingOutcome hearing(String reasonCode, boolean requiresEvidence) {
        return new RoutingOutcome(
                RouteType.DISPUTE_HEARING, reasonCode, requiresEvidence);
    }
}
