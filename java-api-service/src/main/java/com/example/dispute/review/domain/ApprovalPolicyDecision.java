package com.example.dispute.review.domain;

import java.util.List;

public record ApprovalPolicyDecision(
        String policyVersion,
        String requiredRole,
        int requiredReviewCount,
        String priority,
        List<String> requiredApprovals,
        List<String> riskFlags,
        List<String> allowedActions,
        List<String> forbiddenActions,
        boolean autoApprove) {

    public ApprovalPolicyDecision {
        requiredApprovals = List.copyOf(requiredApprovals);
        riskFlags = List.copyOf(riskFlags);
        allowedActions = List.copyOf(allowedActions);
        forbiddenActions = List.copyOf(forbiddenActions);
        if (autoApprove) {
            throw new IllegalArgumentException(
                    "AI Native disputes can never be auto-approved");
        }
    }
}
