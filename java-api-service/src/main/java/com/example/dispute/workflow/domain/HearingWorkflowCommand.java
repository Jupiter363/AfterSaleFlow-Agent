package com.example.dispute.workflow.domain;

import java.time.Duration;

public record HearingWorkflowCommand(
        String caseId,
        String workflowId,
        long dossierVersion,
        Duration evidenceWaitTimeout,
        int maxEvidenceRounds) {}
