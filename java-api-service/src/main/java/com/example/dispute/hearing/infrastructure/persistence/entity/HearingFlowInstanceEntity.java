package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowStatus;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/** Authoritative cursor for one hearing_flow.v2 execution. */
@Entity
@Table(name = "hearing_flow_instance")
public class HearingFlowInstanceEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false, unique = true)
    private String caseId;

    @Column(name = "hearing_state_id", length = 64, nullable = false, unique = true)
    private String hearingStateId;

    @Column(name = "schema_version", length = 32, nullable = false)
    private String schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", length = 64, nullable = false)
    private HearingFlowStage currentStage;

    @Column(name = "stage_sequence", nullable = false)
    private int stageSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_status", length = 32, nullable = false)
    private HearingFlowStatus flowStatus;

    @Column(name = "shared_deadline_at")
    private Instant sharedDeadlineAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected HearingFlowInstanceEntity() {}

    private HearingFlowInstanceEntity(String id) {
        super(id);
    }

    public static HearingFlowInstanceEntity start(
            String id,
            String caseId,
            String hearingStateId,
            Instant now,
            String actorId) {
        HearingFlowInstanceEntity entity = new HearingFlowInstanceEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.hearingStateId = required(hearingStateId, "hearingStateId");
        entity.schemaVersion = "hearing_flow.v2";
        entity.currentStage = HearingFlowStage.COURT_PREPARING;
        entity.stageSequence = 1;
        entity.flowStatus = HearingFlowStatus.ACTIVE;
        entity.createdAt = required(now, "now");
        entity.updatedAt = now;
        entity.createdBy = required(actorId, "actorId");
        entity.updatedBy = actorId;
        return entity;
    }

    public void advance(
            HearingFlowStage nextStage,
            int nextSequence,
            Instant sharedDeadlineAt,
            Instant now,
            String actorId) {
        if (flowStatus == HearingFlowStatus.CLOSED || flowStatus == HearingFlowStatus.FAILED) {
            throw new IllegalStateException("terminal hearing flow cannot advance");
        }
        if (nextSequence != stageSequence + 1) {
            throw new IllegalArgumentException("hearing stage sequence must increase by one");
        }
        requireDeadlineShape(nextStage, sharedDeadlineAt);
        this.currentStage = required(nextStage, "nextStage");
        this.stageSequence = nextSequence;
        this.sharedDeadlineAt = sharedDeadlineAt;
        this.flowStatus =
                nextStage == HearingFlowStage.HUMAN_REVIEW_OPEN
                        ? HearingFlowStatus.HUMAN_REVIEW
                        : nextStage == HearingFlowStage.CLOSED
                                ? HearingFlowStatus.CLOSED
                                : HearingFlowStatus.ACTIVE;
        this.updatedAt = required(now, "now");
        this.updatedBy = required(actorId, "actorId");
    }

    public void fail(Instant now, String actorId) {
        if (flowStatus == HearingFlowStatus.CLOSED) {
            throw new IllegalStateException("closed hearing flow cannot fail");
        }
        this.flowStatus = HearingFlowStatus.FAILED;
        this.sharedDeadlineAt = null;
        this.updatedAt = required(now, "now");
        this.updatedBy = required(actorId, "actorId");
    }

    public void resumeFailedAgentStage(
            HearingFlowStage expectedStage, Instant now, String actorId) {
        if (flowStatus != HearingFlowStatus.FAILED) {
            throw new IllegalStateException("only a failed hearing flow can resume an Agent stage");
        }
        if (currentStage != required(expectedStage, "expectedStage")) {
            throw new IllegalStateException("failed hearing flow is no longer at the retry stage");
        }
        if (currentStage.hasSharedPartyDeadline()) {
            throw new IllegalStateException("party-open hearing stage cannot resume as an Agent stage");
        }
        this.flowStatus = HearingFlowStatus.ACTIVE;
        this.sharedDeadlineAt = null;
        this.updatedAt = required(now, "now");
        this.updatedBy = required(actorId, "actorId");
    }

    public void shortenSharedDeadline(Instant deadline, Instant now, String actorId) {
        if (!currentStage.hasSharedPartyDeadline() || sharedDeadlineAt == null) {
            throw new IllegalStateException("current hearing stage has no shared deadline");
        }
        Instant requiredDeadline = required(deadline, "deadline");
        if (!requiredDeadline.isBefore(sharedDeadlineAt)) {
            return;
        }
        this.sharedDeadlineAt = requiredDeadline;
        this.updatedAt = required(now, "now");
        this.updatedBy = required(actorId, "actorId");
    }

    public String getCaseId() { return caseId; }
    public String getHearingStateId() { return hearingStateId; }
    public String getSchemaVersion() { return schemaVersion; }
    public HearingFlowStage getCurrentStage() { return currentStage; }
    public int getStageSequence() { return stageSequence; }
    public HearingFlowStatus getFlowStatus() { return flowStatus; }
    public Instant getSharedDeadlineAt() { return sharedDeadlineAt; }

    private static void requireDeadlineShape(HearingFlowStage stage, Instant deadline) {
        if (stage.hasSharedPartyDeadline() != (deadline != null)) {
            throw new IllegalArgumentException(
                    "shared deadline is required only for party-open hearing stages");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
