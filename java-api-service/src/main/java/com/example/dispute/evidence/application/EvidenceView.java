package com.example.dispute.evidence.application;

import java.time.OffsetDateTime;

public record EvidenceView(
        String id,
        String caseId,
        String evidenceType,
        String sourceType,
        String fileBucket,
        String fileObjectKey,
        String fileHash,
        String originalFilename,
        String contentType,
        long fileSize,
        String parseStatus,
        String visibility,
        boolean desensitized,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt) {}
