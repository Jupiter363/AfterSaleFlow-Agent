package com.example.dispute.workflow.domain;

public record HearingAnalysisActivityCommand(
        String caseId,
        String workflowId,
        int roundNo,
        boolean evidenceTimedOut,
        boolean evidenceReceived,
        boolean finalConvergence,
        int maxStatementRounds) {

    public HearingAnalysisActivityCommand(
            String caseId,
            String workflowId,
            int roundNo,
            boolean evidenceTimedOut,
            boolean evidenceReceived) {
        this(
                caseId,
                workflowId,
                roundNo,
                evidenceTimedOut,
                evidenceReceived,
                false,
                0);
    }

    public boolean mustProduceFinalPlan() {
        return finalConvergence || evidenceTimedOut;
    }

    public boolean allowSupplementalRequest() {
        return !mustProduceFinalPlan();
    }
}
