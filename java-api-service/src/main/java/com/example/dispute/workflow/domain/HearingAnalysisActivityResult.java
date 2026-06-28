package com.example.dispute.workflow.domain;

public record HearingAnalysisActivityResult(
        boolean requiresAdditionalEvidence,
        boolean manualRequired,
        String draftId,
        String hearingStatus) {}
