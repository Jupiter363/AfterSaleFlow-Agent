package com.example.dispute.review.domain;

import java.util.List;

public record ApprovalPolicyDecision(
        String requiredRole,
        String priority,
        List<String> requiredApprovals,
        List<String> riskFlags,
        boolean autoApprove) {

    public ApprovalPolicyDecision {
        requiredApprovals = List.copyOf(requiredApprovals);
        riskFlags = List.copyOf(riskFlags);
    }
}
