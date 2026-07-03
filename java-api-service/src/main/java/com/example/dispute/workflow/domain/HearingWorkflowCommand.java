package com.example.dispute.workflow.domain;

import java.time.Duration;

public record HearingWorkflowCommand(
        String caseId,
        String workflowId,
        long dossierVersion,
        Duration evidenceWaitTimeout,
        int maxEvidenceRounds,
        Duration hearingWaitTimeout,
        int maxHearingRounds) {

    public HearingWorkflowCommand(
            String caseId,
            String workflowId,
            long dossierVersion,
            Duration evidenceWaitTimeout,
            int maxEvidenceRounds) {
        this(
                caseId,
                workflowId,
                dossierVersion,
                evidenceWaitTimeout,
                maxEvidenceRounds,
                Duration.ZERO,
                0);
    }
}
