package com.example.dispute.review.application;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.fasterxml.jackson.databind.JsonNode;

public record ReviewDecisionCommand(
        ApprovalDecisionType decision,
        String reason,
        JsonNode approvedPlan,
        String idempotencyKey) {

    public ReviewDecisionCommand {
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("review reason is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
    }
}
