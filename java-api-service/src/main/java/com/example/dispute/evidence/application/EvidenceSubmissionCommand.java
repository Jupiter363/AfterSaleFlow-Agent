package com.example.dispute.evidence.application;

import java.util.List;

public record EvidenceSubmissionCommand(List<String> evidenceIds, String batchNote) {
    public EvidenceSubmissionCommand {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        batchNote = batchNote == null ? "" : batchNote.trim();
    }
}
