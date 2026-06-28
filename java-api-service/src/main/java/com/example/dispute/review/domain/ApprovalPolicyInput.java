package com.example.dispute.review.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record ApprovalPolicyInput(
        RiskLevel riskLevel,
        BigDecimal totalAmount,
        List<String> actionTypes,
        String disputeType,
        boolean evidenceInsufficient) {

    public ApprovalPolicyInput {
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        totalAmount = totalAmount == null ? BigDecimal.ZERO : totalAmount;
        actionTypes = actionTypes == null ? List.of() : List.copyOf(actionTypes);
        if (actionTypes.isEmpty()) {
            throw new IllegalArgumentException("actionTypes must not be empty");
        }
    }
}
