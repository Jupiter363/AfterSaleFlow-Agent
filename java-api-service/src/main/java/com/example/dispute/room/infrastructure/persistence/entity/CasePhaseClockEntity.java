package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.PhaseClockStatus;
import com.example.dispute.room.domain.PhaseClockType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Entity
@Table(name = "case_phase_clock")
public class CasePhaseClockEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "room_id", length = 64, nullable = false)
    private String roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "clock_type", length = 32, nullable = false)
    private PhaseClockType clockType;

    @Enumerated(EnumType.STRING)
    @Column(name = "clock_status", length = 32, nullable = false)
    private PhaseClockStatus clockStatus;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "deadline_at", nullable = false)
    private OffsetDateTime deadlineAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "temporal_workflow_id", length = 128, nullable = false)
    private String temporalWorkflowId;

    @Column(name = "temporal_run_id", length = 128)
    private String temporalRunId;

    @Column(name = "completion_reason", length = 64)
    private String completionReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected CasePhaseClockEntity() {}

    private CasePhaseClockEntity(String id) {
        super(id);
    }

    public static CasePhaseClockEntity running(
            String id,
            String caseId,
            String roomId,
            PhaseClockType type,
            OffsetDateTime startedAt,
            OffsetDateTime deadlineAt,
            String workflowId,
            String actorId) {
        if (!deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("deadlineAt must be after startedAt");
        }
        CasePhaseClockEntity entity = new CasePhaseClockEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.roomId = required(roomId, "roomId");
        entity.clockType = Objects.requireNonNull(type, "type must not be null");
        entity.clockStatus = PhaseClockStatus.RUNNING;
        entity.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        entity.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        entity.temporalWorkflowId = required(workflowId, "workflowId");
        entity.createdBy = required(actorId, "actorId");
        entity.updatedBy = actorId;
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

    public PhaseClockType getClockType() {
        return clockType;
    }

    public PhaseClockStatus getClockStatus() {
        return clockStatus;
    }

    public OffsetDateTime getDeadlineAt() {
        return deadlineAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
