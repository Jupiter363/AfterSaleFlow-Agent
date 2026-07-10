package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.ParseStatus;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evidence_item")
public class EvidenceItemEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "dossier_id", length = 64, nullable = false)
    private String dossierId;

    @Column(name = "evidence_type", length = 64, nullable = false)
    private String evidenceType;

    @Column(name = "source_type", length = 64, nullable = false)
    private String sourceType;

    @Column(name = "submitted_by_role", length = 32, nullable = false)
    private String submittedByRole;

    @Column(name = "submitted_by_id", length = 128, nullable = false)
    private String submittedById;

    @Column(name = "file_bucket", length = 128)
    private String fileBucket;

    @Column(name = "file_object_key", length = 512)
    private String fileObjectKey;

    @Column(name = "file_hash", length = 128)
    private String fileHash;

    @Column(name = "original_filename", length = 512)
    private String originalFilename;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "parsed_text", columnDefinition = "text")
    private String parsedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", length = 32, nullable = false)
    private ParseStatus parseStatus;

    @Column(name = "visibility", length = 32, nullable = false)
    private String visibility;

    @Column(name = "desensitized", nullable = false)
    private boolean desensitized;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extraction_json", nullable = false, columnDefinition = "jsonb")
    private String extractionJson;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_status", length = 32, nullable = false)
    private EvidenceSubmissionStatus submissionStatus;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "submission_batch_id", length = 64)
    private String submissionBatchId;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected EvidenceItemEntity() {}

    private EvidenceItemEntity(
            String id,
            String caseId,
            String dossierId,
            String evidenceType,
            String sourceType,
            String submittedByRole,
            String submittedById,
            String fileBucket,
            String fileObjectKey,
            String fileHash,
            String originalFilename,
            String contentType,
            long fileSize,
            String visibility,
            OffsetDateTime occurredAt) {
        super(id);
        this.caseId = caseId;
        this.dossierId = dossierId;
        this.evidenceType = evidenceType;
        this.sourceType = sourceType;
        this.submittedByRole = submittedByRole;
        this.submittedById = submittedById;
        this.fileBucket = fileBucket;
        this.fileObjectKey = fileObjectKey;
        this.fileHash = fileHash;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.parseStatus = ParseStatus.PENDING;
        this.visibility = visibility;
        this.desensitized = false;
        this.metadataJson = "{}";
        this.extractionJson = "{}";
        this.occurredAt = occurredAt;
        this.submissionStatus = EvidenceSubmissionStatus.PENDING_SUBMISSION;
        this.createdBy = submittedById;
        this.updatedBy = submittedById;
    }

    public static EvidenceItemEntity uploaded(
            String id,
            String caseId,
            String dossierId,
            String evidenceType,
            String sourceType,
            String submittedByRole,
            String submittedById,
            String fileBucket,
            String fileObjectKey,
            String fileHash,
            String originalFilename,
            String contentType,
            long fileSize,
            String visibility,
            OffsetDateTime occurredAt) {
        return new EvidenceItemEntity(
                id,
                caseId,
                dossierId,
                evidenceType,
                sourceType,
                submittedByRole,
                submittedById,
                fileBucket,
                fileObjectKey,
                fileHash,
                originalFilename,
                contentType,
                fileSize,
                visibility,
                occurredAt);
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getCaseId() {
        return caseId;
    }

    public String getEvidenceType() {
        return evidenceType;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSubmittedByRole() {
        return submittedByRole;
    }

    public String getSubmittedById() {
        return submittedById;
    }

    public String getFileBucket() {
        return fileBucket;
    }

    public String getFileObjectKey() {
        return fileObjectKey;
    }

    public String getFileHash() {
        return fileHash;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public ParseStatus getParseStatus() {
        return parseStatus;
    }

    public String getVisibility() {
        return visibility;
    }

    public boolean isDesensitized() {
        return desensitized;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public EvidenceSubmissionStatus getSubmissionStatus() {
        return submissionStatus == null
                ? EvidenceSubmissionStatus.SUBMITTED
                : submissionStatus;
    }

    public OffsetDateTime getSubmittedAt() {
        return submittedAt;
    }

    public String getSubmissionBatchId() {
        return submissionBatchId;
    }

    public String getParsedText() {
        return parsedText;
    }

    public String getExtractionJson() {
        return extractionJson;
    }

    public void applyParseSuccess(
            String text, String extractionJson, String actorId) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("parsed text must not be blank");
        }
        this.parsedText = text;
        this.extractionJson = extractionJson;
        this.parseStatus = ParseStatus.SUCCEEDED;
        this.updatedBy = actorId;
    }

    public void applyParseFailure(String extractionJson, String actorId) {
        this.parsedText = null;
        this.extractionJson = extractionJson;
        this.parseStatus = ParseStatus.FAILED;
        this.updatedBy = actorId;
    }

    public void markSubmitted(String batchId, OffsetDateTime submittedAt, String actorId) {
        if (deletedAt != null) {
            throw new IllegalStateException("deleted evidence cannot be submitted");
        }
        if (getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED) {
            throw new IllegalStateException("evidence has already been submitted");
        }
        this.submissionStatus = EvidenceSubmissionStatus.SUBMITTED;
        this.submissionBatchId = batchId;
        this.submittedAt = submittedAt;
        this.updatedBy = actorId;
    }

    public void markSubmittedForParties(
            String batchId, OffsetDateTime submittedAt, String actorId) {
        markSubmitted(batchId, submittedAt, actorId);
        this.visibility = "PARTIES";
    }

    public void deletePending(OffsetDateTime deletedAt, String actorId) {
        if (getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED) {
            throw new IllegalStateException("submitted evidence cannot be deleted");
        }
        this.submissionStatus = EvidenceSubmissionStatus.VOIDED;
        this.deletedAt = deletedAt;
        this.updatedBy = actorId;
    }
}
