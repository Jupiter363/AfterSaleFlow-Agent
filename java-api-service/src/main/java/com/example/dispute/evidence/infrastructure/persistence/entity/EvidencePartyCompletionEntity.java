package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "evidence_party_completion")
public class EvidencePartyCompletionEntity extends AbstractEntity {
    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32, nullable = false)
    private ActorRole participantRole;
    @Column(name = "participant_id", length = 128, nullable = false)
    private String participantId;
    @Column(name = "completion_status", length = 32, nullable = false)
    private String completionStatus;
    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected EvidencePartyCompletionEntity() {}
    private EvidencePartyCompletionEntity(String id) { super(id); }

    public static EvidencePartyCompletionEntity completed(
            String id, String caseId, int dossierVersion, ActorRole role,
            String participantId, String idempotencyKey, Instant now) {
        EvidencePartyCompletionEntity entity = new EvidencePartyCompletionEntity(id);
        entity.caseId = caseId;
        entity.dossierVersion = dossierVersion;
        entity.participantRole = role;
        entity.participantId = participantId;
        entity.completionStatus = "COMPLETED";
        entity.idempotencyKey = idempotencyKey;
        entity.completedAt = now;
        entity.createdAt = now;
        entity.createdBy = participantId;
        return entity;
    }

    public ActorRole getParticipantRole() { return participantRole; }
    public int getDossierVersion() { return dossierVersion; }
    public String getCaseId() { return caseId; }
    public String getCompletionStatus() { return completionStatus; }
}
