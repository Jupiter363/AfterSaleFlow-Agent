package com.example.dispute.hearing.infrastructure.persistence.entity;

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
@Table(name = "settlement_confirmation")
public class SettlementConfirmationEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "proposal_id", length = 64, nullable = false)
    private String proposalId;
    @Column(name = "proposal_version", nullable = false)
    private int proposalVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32, nullable = false)
    private ActorRole participantRole;
    @Column(name = "participant_id", length = 128, nullable = false)
    private String participantId;
    @Column(name = "confirmation_status", length = 32, nullable = false)
    private String confirmationStatus;
    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;
    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected SettlementConfirmationEntity() {}

    private SettlementConfirmationEntity(String id) {
        super(id);
    }

    public static SettlementConfirmationEntity confirmed(
            String id,
            String caseId,
            String proposalId,
            int proposalVersion,
            ActorRole role,
            String actorId,
            String idempotencyKey,
            Instant now) {
        SettlementConfirmationEntity entity = new SettlementConfirmationEntity(id);
        entity.caseId = caseId;
        entity.proposalId = proposalId;
        entity.proposalVersion = proposalVersion;
        entity.participantRole = role;
        entity.participantId = actorId;
        entity.confirmationStatus = "CONFIRMED";
        entity.idempotencyKey = idempotencyKey;
        entity.confirmedAt = now;
        entity.createdAt = now;
        entity.createdBy = actorId;
        return entity;
    }

    public String getProposalId() {
        return proposalId;
    }

    public int getProposalVersion() {
        return proposalVersion;
    }

    public ActorRole getParticipantRole() {
        return participantRole;
    }

    public String getParticipantId() {
        return participantId;
    }
}
