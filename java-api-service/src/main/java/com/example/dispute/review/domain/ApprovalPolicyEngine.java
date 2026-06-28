package com.example.dispute.review.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ApprovalPolicyEngine {

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
        return new ApprovalPolicyDecision(
                "PLATFORM_REVIEWER",
                priority,
                required,
                flags,
                false);
    }
}
