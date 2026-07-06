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
        OffsetDateTime createdAt,
        String submissionStatus,
        OffsetDateTime submittedAt,
        String submissionBatchId) {

    public EvidenceView(
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
            OffsetDateTime createdAt) {
        this(
                id,
                caseId,
                evidenceType,
                sourceType,
                fileBucket,
                fileObjectKey,
                fileHash,
                originalFilename,
                contentType,
                fileSize,
                parseStatus,
                visibility,
                desensitized,
                occurredAt,
                createdAt,
                "SUBMITTED",
                null,
                null);
    }
}
