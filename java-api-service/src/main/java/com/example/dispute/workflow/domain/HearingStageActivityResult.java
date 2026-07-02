package com.example.dispute.workflow.domain;

public record HearingStageActivityResult(
        String stage,
        boolean valid,
        boolean requiresAdditionalEvidence,
        boolean manualRequired,
        String draftId,
        String outputVersion) {}
