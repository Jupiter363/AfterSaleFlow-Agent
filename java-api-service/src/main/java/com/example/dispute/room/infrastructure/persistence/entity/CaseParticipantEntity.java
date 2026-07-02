package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.ParticipantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "case_participant")
public class CaseParticipantEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32, nullable = false)
    private ActorRole participantRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_status", length = 32, nullable = false)
    private ParticipantStatus participantStatus;

    @Column(name = "joined_at")
    private OffsetDateTime joinedAt;

    @Column(name = "invited_at")
    private OffsetDateTime invitedAt;

    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visibility_scope_json", nullable = false, columnDefinition = "jsonb")
    private String visibilityScopeJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected CaseParticipantEntity() {}

    private CaseParticipantEntity(String id) {
        super(id);
    }

    public static CaseParticipantEntity active(
            String id,
            String caseId,
            String actorId,
            ActorRole role,
            OffsetDateTime now,
            String createdBy) {
        return create(
                id,
                caseId,
                actorId,
                role,
                ParticipantStatus.ACTIVE,
                now,
                now,
                createdBy);
    }

    public static CaseParticipantEntity invited(
            String id,
            String caseId,
            String actorId,
            ActorRole role,
            OffsetDateTime now,
            String createdBy) {
        return create(
                id,
                caseId,
                actorId,
                role,
                ParticipantStatus.INVITED,
                null,
                now,
                createdBy);
    }

    private static CaseParticipantEntity create(
            String id,
            String caseId,
            String actorId,
            ActorRole role,
            ParticipantStatus status,
            OffsetDateTime joinedAt,
            OffsetDateTime invitedAt,
            String createdBy) {
        CaseParticipantEntity entity = new CaseParticipantEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.actorId = required(actorId, "actorId");
        entity.participantRole = Objects.requireNonNull(role, "role must not be null");
        entity.participantStatus = status;
        entity.joinedAt = joinedAt;
        entity.invitedAt = invitedAt;
        entity.visibilityScopeJson = "{}";
        entity.createdBy = required(createdBy, "createdBy");
        entity.updatedBy = createdBy;
        return entity;
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

    public String getActorId() {
        return actorId;
    }

    public ActorRole getParticipantRole() {
        return participantRole;
    }

    public ParticipantStatus getParticipantStatus() {
        return participantStatus;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
