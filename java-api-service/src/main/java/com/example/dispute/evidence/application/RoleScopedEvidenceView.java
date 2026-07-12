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
            String submissionBatchId,
            Double authenticityScore,
            Double relevanceScore,
            Double completenessScore,
            Double assessmentConfidence,
            List<String> inspectedModalities,
            List<String> limitations,
            boolean requiresHumanReview,
            List<String> humanReviewReasonCodes,
            List<String> humanReviewInstructions) {

        public Item {
            inspectedModalities =
                    inspectedModalities == null ? List.of() : List.copyOf(inspectedModalities);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            humanReviewReasonCodes =
                    humanReviewReasonCodes == null
                            ? List.of()
                            : List.copyOf(humanReviewReasonCodes);
            humanReviewInstructions =
                    humanReviewInstructions == null
                            ? List.of()
                            : List.copyOf(humanReviewInstructions);
        }

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
                String parsedText,
                String submissionStatus,
                java.time.OffsetDateTime submittedAt,
                String submissionBatchId) {
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
                    submissionStatus,
                    submittedAt,
                    submissionBatchId,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    List.of());
        }

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
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    false,
                    List.of(),
                    List.of());
        }
    }
}
