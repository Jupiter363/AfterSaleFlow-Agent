package com.example.dispute.workflow.domain;

public record CaseWorkflowResult(
        String caseId,
        String workflowId,
        String workflowStatus,
        String nextStage,
        boolean manualRequired,
        boolean evidenceTimedOut,
        String draftId,
        String remedyPlanId,
        String reviewTaskId) {}
