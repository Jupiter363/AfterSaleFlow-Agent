package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "action_record")
public class ActionRecordEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "plan_id", length = 64, nullable = false)
    private String planId;

    @Column(name = "approval_record_id", length = 64, nullable = false)
    private String approvalRecordId;

    @Column(name = "action_type", length = 64, nullable = false)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "idempotency_key", length = 128, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "approved_by", length = 128, nullable = false)
    private String approvedBy;

    @Column(name = "executed_by", length = 128, nullable = false)
    private String executedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
    private String requestJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
    private String resultJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", length = 32, nullable = false)
    private ExecutionStatus executionStatus;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "execution_time")
    private OffsetDateTime executionTime;

    @Column(name = "review_packet_id", length = 64)
    private String reviewPacketId;

    @Column(name = "action_snapshot_hash", length = 128)
    private String actionSnapshotHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceRefsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_refs_json", nullable = false, columnDefinition = "jsonb")
    private String ruleRefsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_run_refs_json", nullable = false, columnDefinition = "jsonb")
    private String agentRunRefsJson;

    @Column(name = "external_result_ref", length = 256)
    private String externalResultRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected ActionRecordEntity() {}

    private ActionRecordEntity(String id) {
        super(id);
    }

    public static ActionRecordEntity running(
            String id,
            String caseId,
            String planId,
            String approvalRecordId,
            String actionType,
            RiskLevel riskLevel,
            String idempotencyKey,
            String approvedBy,
            String executedBy,
            String requestJson) {
        ActionRecordEntity record = new ActionRecordEntity(id);
        record.caseId = caseId;
        record.planId = planId;
        record.approvalRecordId = approvalRecordId;
        record.actionType = actionType;
        record.riskLevel = riskLevel;
        record.idempotencyKey = idempotencyKey;
        record.approvedBy = approvedBy;
        record.executedBy = executedBy;
        record.requestJson = requestJson;
        record.resultJson = "{}";
        record.executionStatus = ExecutionStatus.RUNNING;
        record.attemptCount = 1;
        record.evidenceRefsJson = "[]";
        record.ruleRefsJson = "[]";
        record.agentRunRefsJson = "[]";
        record.createdBy = executedBy;
        return record;
    }

    public static ActionRecordEntity runningGoverned(
            String id,
            String caseId,
            String planId,
            String approvalRecordId,
            String actionType,
            RiskLevel riskLevel,
            String idempotencyKey,
            String approvedBy,
            String executedBy,
            String requestJson,
            String reviewPacketId,
            String actionSnapshotHash,
            String evidenceRefsJson,
            String ruleRefsJson,
            String agentRunRefsJson) {
        ActionRecordEntity record =
                running(
                        id,
                        caseId,
                        planId,
                        approvalRecordId,
                        actionType,
                        riskLevel,
                        idempotencyKey,
                        approvedBy,
                        executedBy,
                        requestJson);
        record.reviewPacketId = reviewPacketId;
        record.actionSnapshotHash = actionSnapshotHash;
        record.evidenceRefsJson = evidenceRefsJson;
        record.ruleRefsJson = ruleRefsJson;
        record.agentRunRefsJson = agentRunRefsJson;
        return record;
    }

    public void retry(String executedBy, String requestJson) {
        if (executionStatus == ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("succeeded action cannot be retried");
        }
        this.executedBy = executedBy;
        this.requestJson = requestJson;
        this.resultJson = "{}";
        this.executionStatus = ExecutionStatus.RUNNING;
        this.errorCode = null;
        this.errorMessage = null;
        this.executionTime = null;
        this.attemptCount += 1;
    }

    public void succeed(String resultJson) {
        succeed(resultJson, null);
    }

    public void succeed(String resultJson, String externalResultRef) {
        this.resultJson = resultJson;
        this.externalResultRef = externalResultRef;
        this.executionStatus = ExecutionStatus.SUCCEEDED;
        this.errorCode = null;
        this.errorMessage = null;
        this.executionTime = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void fail(String errorCode, String errorMessage, String resultJson) {
        this.resultJson = resultJson;
        this.executionStatus = ExecutionStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.executionTime = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getCaseId() {
        return caseId;
    }

    public String getPlanId() {
        return planId;
    }

    public String getApprovalRecordId() {
        return approvalRecordId;
    }

    public String getActionType() {
        return actionType;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public String getExecutedBy() {
        return executedBy;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public String getResultJson() {
        return resultJson;
    }

    public ExecutionStatus getExecutionStatus() {
        return executionStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public OffsetDateTime getExecutionTime() {
        return executionTime;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getReviewPacketId() {
        return reviewPacketId;
    }

    public String getActionSnapshotHash() {
        return actionSnapshotHash;
    }

    public String getEvidenceRefsJson() {
        return evidenceRefsJson;
    }

    public String getRuleRefsJson() {
        return ruleRefsJson;
    }

    public String getAgentRunRefsJson() {
        return agentRunRefsJson;
    }

    public String getExternalResultRef() {
        return externalResultRef;
    }
}
