package com.example.dispute.evidence.api;

import com.example.dispute.evidence.application.EvidenceSubmissionCommand;
import jakarta.validation.constraints.Size;
import java.util.List;

public record EvidenceSubmissionRequest(
        @Size(min = 1, max = 50) List<String> evidenceIds,
        @Size(max = 1000) String batchNote) {

    EvidenceSubmissionCommand toCommand() {
        return new EvidenceSubmissionCommand(evidenceIds, batchNote);
    }
}
