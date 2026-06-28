package com.example.dispute.workflow.domain;

import java.util.List;

public record PartyEvidenceSignal(
        String partyType, String submissionId, List<String> evidenceIds) {

    public PartyEvidenceSignal {
        if (partyType == null || partyType.isBlank()) {
            throw new IllegalArgumentException("partyType must not be blank");
        }
        if (submissionId == null || submissionId.isBlank()) {
            throw new IllegalArgumentException("submissionId must not be blank");
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
