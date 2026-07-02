package com.example.dispute.workflow.domain;

import java.util.List;

public record EvidenceSubmissionSignal(
        String submissionId,
        String partyRole,
        List<String> evidenceRefs) {

    public EvidenceSubmissionSignal {
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
