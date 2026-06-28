package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.HearingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "hearing_state")
public class HearingStateEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false, unique = true)
    private String caseId;

    @Column(name = "workflow_id", length = 128, nullable = false, unique = true)
    private String workflowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "hearing_status", length = 64, nullable = false)
    private HearingStatus hearingStatus;

    @Column(name = "current_node", length = 64)
    private String currentNode;

    @Column(name = "round_no", nullable = false)
    private int roundNo;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "manual_required", nullable = false)
    private boolean manualRequired;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_state_json", nullable = false, columnDefinition = "jsonb")
    private String graphStateJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_requests_json", nullable = false, columnDefinition = "jsonb")
    private String pendingRequestsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manual_flags_json", nullable = false, columnDefinition = "jsonb")
    private String manualFlagsJson;

    @Column(name = "waiting_until")
    private OffsetDateTime waitingUntil;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected HearingStateEntity() {}

    private HearingStateEntity(String id) {
        super(id);
    }

    public static HearingStateEntity start(
            String id, String caseId, String workflowId, String actorId) {
        HearingStateEntity state = new HearingStateEntity(id);
        state.caseId = caseId;
        state.workflowId = workflowId;
        state.hearingStatus = HearingStatus.RUNNING;
        state.currentNode = "C0_HEARING_CONTROLLER";
        state.graphStateJson = "{}";
        state.pendingRequestsJson = "[]";
        state.manualFlagsJson = "[]";
        state.createdBy = actorId;
        state.updatedBy = actorId;
        return state;
    }

    public void applyAnalysis(
            int roundNo,
            String currentNode,
            BigDecimal confidence,
            boolean requiresEvidence,
            boolean manualRequired,
            String graphStateJson,
            String pendingRequestsJson,
            String manualFlagsJson,
            OffsetDateTime waitingUntil,
            String actorId) {
        this.roundNo = roundNo;
        this.currentNode = currentNode;
        this.confidence = confidence;
        this.manualRequired |= manualRequired;
        this.graphStateJson = graphStateJson;
        this.pendingRequestsJson = pendingRequestsJson;
        this.manualFlagsJson = manualFlagsJson;
        this.waitingUntil = requiresEvidence ? waitingUntil : null;
        this.hearingStatus =
                requiresEvidence
                        ? HearingStatus.WAITING_EVIDENCE
                        : HearingStatus.RUNNING;
        this.updatedBy = actorId;
    }

    public void markRunning(String actorId) {
        hearingStatus = HearingStatus.RUNNING;
        waitingUntil = null;
        updatedBy = actorId;
    }

    public void complete(boolean manualRequired, String actorId) {
        this.manualRequired |= manualRequired;
        this.hearingStatus = HearingStatus.COMPLETED;
        this.currentNode = "C6_ADJUDICATION_DRAFT";
        this.waitingUntil = null;
        this.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedBy = actorId;
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

    public String getCaseId() { return caseId; }
    public String getWorkflowId() { return workflowId; }
    public HearingStatus getHearingStatus() { return hearingStatus; }
    public String getCurrentNode() { return currentNode; }
    public int getRoundNo() { return roundNo; }
    public BigDecimal getConfidence() { return confidence; }
    public boolean isManualRequired() { return manualRequired; }
    public String getPendingRequestsJson() { return pendingRequestsJson; }
    public OffsetDateTime getWaitingUntil() { return waitingUntil; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
