package com.example.dispute.hearing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** One terminal evidence batch for the authenticated party. */
public record HearingEvidenceBatchRequest(
        @NotBlank @Pattern(regexp = "hearing_evidence_batch\\.v1") String schemaVersion,
        @NotBlank @Size(max = 128) String requestSetId,
        @Size(max = 10) List<@NotBlank @Size(max = 128) String> requestIds,
        @Size(max = 50) List<@NotBlank @Size(max = 128) String> evidenceIds,
        @Size(max = 1_000) String batchNote) {

    public HearingEvidenceBatchRequest {
        requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        batchNote = batchNote == null ? "" : batchNote.strip();
    }
}
