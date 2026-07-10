package com.example.dispute.evidence.application;

import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import java.util.List;

public record RoleScopedEvidenceView(String caseId, String initiatorRole, List<Item> items) {

    public RoleScopedEvidenceView(String caseId, List<Item> items) {
        this(caseId, null, items);
    }

    public record Item(
            String evidenceId,
            String evidenceType,
            String submittedByRole,
            String visibility,
            String contentUrl,
            boolean redacted,
            EvidenceVerificationStatus verificationStatus,
            Double confidenceScore,
            String confidenceLevel,
            String verificationFeedback,
            String sourceType,
            String originalFilename,
            String parsedText,
            String submissionStatus,
            java.time.OffsetDateTime submittedAt,
            String submissionBatchId) {

        public Item(
                String evidenceId,
                String evidenceType,
                String submittedByRole,
                String visibility,
                String contentUrl,
                boolean redacted,
                EvidenceVerificationStatus verificationStatus,
                Double confidenceScore,
                String confidenceLevel,
                String verificationFeedback,
                String sourceType,
                String originalFilename,
                String parsedText) {
            this(
                    evidenceId,
                    evidenceType,
                    submittedByRole,
                    visibility,
                    contentUrl,
                    redacted,
                    verificationStatus,
                    confidenceScore,
                    confidenceLevel,
                    verificationFeedback,
                    sourceType,
                    originalFilename,
                    parsedText,
                    "SUBMITTED",
                    null,
                    null);
        }
    }
}
