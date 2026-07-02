package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dispute_submission")
public class PartySubmissionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "evidence_request_id", length = 64)
    private String evidenceRequestId;
    @Column(name = "submitted_by_role", length = 32, nullable = false)
    private String submittedByRole;
    @Column(name = "submitted_by_id", length = 128, nullable = false)
    private String submittedById;
    @Column(name = "submission_type", length = 64, nullable = false)
    private String submissionType;
    @Column(name = "submission_text", columnDefinition = "text")
    private String submissionText;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submission_json", nullable = false, columnDefinition = "jsonb")
    private String submissionJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachment_ids_json", nullable = false, columnDefinition = "jsonb")
    private String attachmentIdsJson;
    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected PartySubmissionEntity() {}

    private PartySubmissionEntity(String id) { super(id); }

    public static PartySubmissionEntity submit(
            String id, String caseId, String requestId, String role, String actorId,
            String type, String text, String submissionJson, String attachmentIdsJson) {
        PartySubmissionEntity submission = new PartySubmissionEntity(id);
        submission.caseId = caseId;
        submission.evidenceRequestId = requestId;
        submission.submittedByRole = role;
        submission.submittedById = actorId;
        submission.submissionType = type;
        submission.submissionText = text;
        submission.submissionJson = submissionJson;
        submission.attachmentIdsJson = attachmentIdsJson;
        submission.acceptedAt = OffsetDateTime.now(ZoneOffset.UTC);
        submission.createdBy = actorId;
        submission.updatedBy = actorId;
        return submission;
    }

    @PrePersist void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }
    @PreUpdate void preUpdate() { updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }
    public String getCaseId() { return caseId; }
    public String getSubmittedByRole() { return submittedByRole; }
    public String getSubmittedById() { return submittedById; }
    public String getSubmissionText() { return submissionText; }
    public String getAttachmentIdsJson() { return attachmentIdsJson; }
}
