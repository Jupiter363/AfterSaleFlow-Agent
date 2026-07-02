package com.example.dispute.review.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ApprovalPolicyEngine {

    private static final Set<String> GOVERNED_ACTIONS =
            Set.of(
                    "REFUND",
                    "RESHIP",
                    "REPLACE",
                    "CLOSE_AFTER_SALE",
                    "REJECT_AFTER_SALE",
                    "QUERY_LOGISTICS",
                    "NOTIFY_USER",
                    "NOTIFY_MERCHANT");

    private final BigDecimal refundThreshold;
    private final BigDecimal reshipThreshold;

    public ApprovalPolicyEngine(
            BigDecimal refundThreshold, BigDecimal reshipThreshold) {
        this.refundThreshold = refundThreshold;
        this.reshipThreshold = reshipThreshold;
    }

    public ApprovalPolicyDecision evaluate(ApprovalPolicyInput input) {
        List<String> required =
                new ArrayList<>(List.of("PLATFORM_HUMAN_REVIEW"));
        List<String> flags = new ArrayList<>();
        boolean riskReview = input.riskLevel().ordinal() >= RiskLevel.HIGH.ordinal();
        if (input.actionTypes().contains("REFUND")
                && input.totalAmount().compareTo(refundThreshold) >= 0) {
            flags.add("HIGH_VALUE_REFUND");
            riskReview = true;
        }
        if ((input.actionTypes().contains("RESHIP")
                        || input.actionTypes().contains("REPLACE"))
                && input.totalAmount().compareTo(reshipThreshold) >= 0) {
            flags.add("HIGH_VALUE_RESHIP");
            riskReview = true;
        }
        if (input.disputeType() != null
                && input.disputeType().contains("ITEM_SWAP")) {
            flags.add("ITEM_SWAP_DISPUTE");
            riskReview = true;
        }
        if (input.evidenceInsufficient()) {
            flags.add("EVIDENCE_INSUFFICIENT");
            riskReview = true;
        }
        if (riskReview) {
            required.add("RISK_CONTROL_REVIEW");
        }
        String priority =
                riskReview
                        ? "URGENT"
                        : input.riskLevel() == RiskLevel.MEDIUM ? "HIGH" : "NORMAL";
        List<String> allowedActions =
                List.copyOf(new LinkedHashSet<>(input.actionTypes()));
        List<String> forbiddenActions =
                GOVERNED_ACTIONS.stream()
                        .filter(action -> !allowedActions.contains(action))
                        .sorted()
                        .toList();
        return new ApprovalPolicyDecision(
                "approval-policy-v1",
                "PLATFORM_REVIEWER",
                1,
                priority,
                required,
                flags,
                allowedActions,
                forbiddenActions,
                false);
    }
}
