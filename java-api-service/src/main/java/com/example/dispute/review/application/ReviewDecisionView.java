package com.example.dispute.review.application;

public record ReviewDecisionView(
        String approvalRecordId,
        String taskId,
        String caseId,
        String decision,
        String taskStatus,
        String caseStatus,
        boolean executionAllowed) {}
