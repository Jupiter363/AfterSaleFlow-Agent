package com.example.dispute.review.application;

public record PostReviewOrchestrationResult(
        String approvalRecordId,
        String caseId,
        String status,
        boolean executionAttempted,
        boolean closureAttempted,
        String message) {}
