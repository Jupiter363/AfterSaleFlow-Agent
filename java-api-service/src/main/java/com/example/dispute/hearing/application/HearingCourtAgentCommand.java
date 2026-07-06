package com.example.dispute.hearing.application;

import java.util.List;

public record HearingCourtAgentCommand(
        String caseId,
        String workflowId,
        String orderId,
        String afterSaleId,
        String logisticsId,
        String disputeType,
        String title,
        String caseDescription,
        String riskLevel,
        int roundNo,
        int dossierVersion,
        boolean finalRound,
        String roundStatus,
        String stopReason,
        String roundSummaryJson,
        List<PartySubmission> partySubmissions) {

    public HearingCourtAgentCommand {
        partySubmissions = partySubmissions == null ? List.of() : List.copyOf(partySubmissions);
    }

    public record PartySubmission(
            String participantRole,
            String participantId,
            String submissionSource,
            String submissionJson) {}
}
