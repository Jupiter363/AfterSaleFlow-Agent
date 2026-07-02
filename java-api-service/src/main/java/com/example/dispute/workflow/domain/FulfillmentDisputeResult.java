package com.example.dispute.workflow.domain;

public record FulfillmentDisputeResult(
        String caseId,
        String workflowId,
        String workflowStatus,
        String nextStage,
        boolean humanReviewRequired,
        boolean manualRequired,
        String draftId,
        String deliberationId,
        String remedyPlanId,
        String reviewId,
        String executionStatus) {}
