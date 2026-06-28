package com.example.dispute.workflow.domain;

public record HearingAnalysisActivityCommand(
        String caseId,
        String workflowId,
        int roundNo,
        boolean evidenceTimedOut,
        boolean evidenceReceived) {}
