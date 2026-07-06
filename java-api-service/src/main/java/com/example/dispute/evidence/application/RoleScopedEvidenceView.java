package com.example.dispute.evidence.application;

import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import java.util.List;

public record RoleScopedEvidenceView(String caseId, List<Item> items) {
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
            String parsedText) {}
}
