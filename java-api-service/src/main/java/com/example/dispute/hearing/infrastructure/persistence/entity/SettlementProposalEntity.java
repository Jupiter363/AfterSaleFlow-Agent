package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.SettlementStatus;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "settlement_proposal")
public class SettlementProposalEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "proposal_version", nullable = false)
    private int proposalVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "proposal_status", length = 32, nullable = false)
    private SettlementStatus proposalStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "proposed_by_role", length = 32, nullable = false)
    private ActorRole proposedByRole;
    @Column(name = "proposed_by_id", length = 128, nullable = false)
    private String proposedById;
    @Column(name = "proposal_text", nullable = false, columnDefinition = "text")
    private String proposalText;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal_json", nullable = false, columnDefinition = "jsonb")
    private String proposalJson;
    @Column(name = "supersedes_proposal_id", length = 64)
    private String supersedesProposalId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "trace_id", length = 128)
    private String traceId;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected SettlementProposalEntity() {}

    private SettlementProposalEntity(String id) {
        super(id);
    }

    public static SettlementProposalEntity propose(
            String id,
            String caseId,
            int version,
            ActorRole role,
            String actorId,
            String proposalText,
            String proposalJson,
            String supersedesProposalId,
            Instant now,
            String traceId) {
        SettlementProposalEntity entity = new SettlementProposalEntity(id);
        entity.caseId = caseId;
        entity.proposalVersion = version;
        entity.proposalStatus = SettlementStatus.PENDING_CONFIRMATION;
        entity.proposedByRole = role;
        entity.proposedById = actorId;
        entity.proposalText = proposalText;
        entity.proposalJson = proposalJson;
        entity.supersedesProposalId = supersedesProposalId;
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.traceId = traceId;
        entity.createdBy = actorId;
        entity.updatedBy = actorId;
        return entity;
    }

    public void supersede(String actorId, Instant now) {
        if (proposalStatus == SettlementStatus.CONFIRMED) {
            throw new IllegalStateException("confirmed settlement cannot be superseded");
        }
        proposalStatus = SettlementStatus.SUPERSEDED;
        updatedAt = now;
        updatedBy = actorId;
    }

    public void confirm(String actorId, Instant now) {
        if (proposalStatus != SettlementStatus.PENDING_CONFIRMATION) {
            throw new IllegalStateException("settlement cannot be confirmed from " + proposalStatus);
        }
        proposalStatus = SettlementStatus.CONFIRMED;
        updatedAt = now;
        updatedBy = actorId;
    }

    public String getCaseId() { return caseId; }
    public int getProposalVersion() { return proposalVersion; }
    public SettlementStatus getProposalStatus() { return proposalStatus; }
    public ActorRole getProposedByRole() { return proposedByRole; }
    public String getProposalText() { return proposalText; }
    public String getProposalJson() { return proposalJson; }
    public Instant getCreatedAt() { return createdAt; }
}
