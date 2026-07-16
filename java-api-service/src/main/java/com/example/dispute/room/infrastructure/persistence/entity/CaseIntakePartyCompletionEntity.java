package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/** Immutable terminal fact for one party's intake participation. */
@Entity
@Immutable
@Table(name = "case_intake_party_completion")
public class CaseIntakePartyCompletionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32, nullable = false)
    private ActorRole participantRole;

    @Column(name = "participant_id", length = 128, nullable = false)
    private String participantId;

    @Column(name = "completion_status", length = 32, nullable = false)
    private String completionStatus;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected CaseIntakePartyCompletionEntity() {}

    private CaseIntakePartyCompletionEntity(String id) {
        super(id);
    }

    public static CaseIntakePartyCompletionEntity terminal(
            String id,
            String caseId,
            ActorRole participantRole,
            String participantId,
            String completionStatus,
            Instant now,
            String actorId) {
        if (participantRole != ActorRole.USER && participantRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("intake participant role must be USER or MERCHANT");
        }
        if (!"COMPLETED".equals(completionStatus) && !"TIMED_OUT".equals(completionStatus)) {
            throw new IllegalArgumentException("unsupported intake completion status");
        }
        CaseIntakePartyCompletionEntity entity = new CaseIntakePartyCompletionEntity(id);
        entity.caseId = required(caseId, "caseId");
        entity.participantRole = participantRole;
        entity.participantId = required(participantId, "participantId");
        entity.completionStatus = completionStatus;
        entity.completedAt = Objects.requireNonNull(now, "now must not be null");
        entity.createdAt = now;
        entity.createdBy = required(actorId, "actorId");
        return entity;
    }

    public String getCaseId() {
        return caseId;
    }

    public ActorRole getParticipantRole() {
        return participantRole;
    }

    public String getParticipantId() {
        return participantId;
    }

    public String getCompletionStatus() {
        return completionStatus;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
