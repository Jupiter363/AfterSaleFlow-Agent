package com.example.dispute.workflow.domain;

public record HearingWorkflowResult(
        String draftId,
        boolean manualRequired,
        boolean evidenceTimedOut,
        long dossierVersion,
        String status) {}
