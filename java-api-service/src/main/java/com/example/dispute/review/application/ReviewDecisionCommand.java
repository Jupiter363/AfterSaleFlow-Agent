package com.example.dispute.review.application;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.fasterxml.jackson.databind.JsonNode;

public record ReviewDecisionCommand(
        ApprovalDecisionType decision,
        String reason,
        JsonNode approvedPlan,
        String idempotencyKey) {}
