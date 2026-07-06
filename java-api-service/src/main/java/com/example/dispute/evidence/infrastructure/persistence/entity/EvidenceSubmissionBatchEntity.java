package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evidence_submission_batch")
public class EvidenceSubmissionBatchEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "actor_role", length = 32, nullable = false)
    private String actorRole;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_ids_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceIdsJson;

    @Column(name = "batch_note", columnDefinition = "text")
    private String batchNote;

    @Column(name = "submit_status", length = 32, nullable = false)
    private String submitStatus;

    @Column(name = "room_message_id", length = 64)
    private String roomMessageId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected EvidenceSubmissionBatchEntity() {}

    private EvidenceSubmissionBatchEntity(String id) {
        super(id);
    }

    public static EvidenceSubmissionBatchEntity submitted(
            String id,
            String caseId,
            String actorRole,
            String actorId,
            String evidenceIdsJson,
            String batchNote,
            String idempotencyKey,
            Instant submittedAt) {
        EvidenceSubmissionBatchEntity entity = new EvidenceSubmissionBatchEntity(id);
        entity.caseId = caseId;
        entity.actorRole = actorRole;
        entity.actorId = actorId;
        entity.evidenceIdsJson = evidenceIdsJson;
        entity.batchNote = batchNote;
        entity.submitStatus = "SUBMITTED";
        entity.idempotencyKey = idempotencyKey;
        entity.submittedAt = submittedAt;
        entity.createdAt = submittedAt;
        entity.createdBy = actorId;
        return entity;
    }

    public void attachRoomMessage(String roomMessageId) {
        this.roomMessageId = roomMessageId;
    }

    public String getCaseId() { return caseId; }

    public String getActorRole() { return actorRole; }

    public String getActorId() { return actorId; }

    public String getEvidenceIdsJson() { return evidenceIdsJson; }

    public String getBatchNote() { return batchNote; }

    public String getSubmitStatus() { return submitStatus; }

    public String getRoomMessageId() { return roomMessageId; }

    public String getIdempotencyKey() { return idempotencyKey; }

    public Instant getSubmittedAt() { return submittedAt; }
}
