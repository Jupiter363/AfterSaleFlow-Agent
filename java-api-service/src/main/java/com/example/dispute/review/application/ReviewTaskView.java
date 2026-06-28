package com.example.dispute.review.application;

import java.time.OffsetDateTime;

public record ReviewTaskView(
        String id,
        String caseId,
        String planId,
        String packetId,
        String status,
        String priority,
        String requiredRole,
        String assignedReviewerId,
        OffsetDateTime dueAt,
        OffsetDateTime createdAt) {}
