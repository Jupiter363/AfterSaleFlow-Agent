package com.example.dispute.workflow.domain;

public record HearingWorkflowResult(
        String draftId,
        boolean manualRequired,
        boolean evidenceTimedOut,
        long dossierVersion,
        String status,
        String stopReason) {

    public HearingWorkflowResult(
            String draftId,
            boolean manualRequired,
            boolean evidenceTimedOut,
            long dossierVersion,
            String status) {
        this(
                draftId,
                manualRequired,
                evidenceTimedOut,
                dossierVersion,
                status,
                null);
    }
}
