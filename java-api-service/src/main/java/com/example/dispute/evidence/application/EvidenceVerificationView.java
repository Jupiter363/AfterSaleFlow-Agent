package com.example.dispute.evidence.application;

import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import java.time.Instant;

public record EvidenceVerificationView(
        String id,
        String evidenceId,
        int version,
        EvidenceVerificationStatus status,
        boolean requiresHumanReview,
        boolean includeInFrozenDossier,
        boolean auditable,
        Instant verifiedAt) {}
