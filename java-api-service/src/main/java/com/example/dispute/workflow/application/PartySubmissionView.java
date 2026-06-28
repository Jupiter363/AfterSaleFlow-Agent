package com.example.dispute.workflow.application;

import java.util.List;

public record PartySubmissionView(
        String submissionId,
        String caseId,
        String partyType,
        List<String> evidenceIds,
        String workflowId,
        String signalStatus) {}
