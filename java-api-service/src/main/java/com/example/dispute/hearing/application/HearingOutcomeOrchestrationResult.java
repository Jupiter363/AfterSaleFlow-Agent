package com.example.dispute.hearing.application;

public record HearingOutcomeOrchestrationResult(
        String caseId,
        String remedyPlanId,
        String reviewTaskId,
        boolean createdRemedy,
        boolean createdReviewTask,
        String status) {}
