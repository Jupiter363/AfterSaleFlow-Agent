package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingStopReason;
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
@Table(name = "hearing_round")
public class HearingRoundEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "hearing_state_id", length = 64)
    private String hearingStateId;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "round_status", length = 32, nullable = false)
    private HearingRoundStatus roundStatus;
    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 64)
    private HearingStopReason stopReason;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json", nullable = false, columnDefinition = "jsonb")
    private String summaryJson;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected HearingRoundEntity() {}

    private HearingRoundEntity(String id) {
        super(id);
    }

    public static HearingRoundEntity open(
            String id,
            String caseId,
            String hearingStateId,
            int roundNo,
            int dossierVersion,
            Instant now,
            String actorId) {
        if (roundNo < 1 || roundNo > 3) {
            throw new IllegalArgumentException("roundNo must be between 1 and 3");
        }
        HearingRoundEntity entity = new HearingRoundEntity(id);
        entity.caseId = caseId;
        entity.hearingStateId = hearingStateId;
        entity.roundNo = roundNo;
        entity.roundStatus = HearingRoundStatus.OPEN;
        entity.dossierVersion = dossierVersion;
        entity.openedAt = now;
        entity.summaryJson = "{}";
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.createdBy = actorId;
        entity.updatedBy = actorId;
        return entity;
    }

    public void complete(
            String summaryJson,
            HearingStopReason stopReason,
            Instant now,
            String actorId) {
        if (roundStatus != HearingRoundStatus.OPEN
                && roundStatus != HearingRoundStatus.WAITING) {
            throw new IllegalStateException("round is already closed");
        }
        this.summaryJson = summaryJson;
        this.stopReason = stopReason;
        this.roundStatus =
                stopReason == HearingStopReason.MAX_ROUNDS
                                || stopReason == HearingStopReason.DEADLINE_EXPIRED
                        ? HearingRoundStatus.FORCED_CLOSED
                        : HearingRoundStatus.COMPLETED;
        this.closedAt = now;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    public int getRoundNo() { return roundNo; }
    public HearingRoundStatus getRoundStatus() { return roundStatus; }
    public int getDossierVersion() { return dossierVersion; }
    public HearingStopReason getStopReason() { return stopReason; }
    public String getSummaryJson() { return summaryJson; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosedAt() { return closedAt; }
}
