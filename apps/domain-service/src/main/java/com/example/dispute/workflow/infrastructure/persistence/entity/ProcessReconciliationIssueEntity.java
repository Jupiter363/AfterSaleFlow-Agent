package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationScope;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationSeverity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "process_reconciliation_issue")
public class ProcessReconciliationIssueEntity extends AbstractEntity {

    @Column(name = "issue_key", length = 128, nullable = false, updatable = false)
    private String issueKey;

    @Column(name = "tenant_surrogate", length = 128, nullable = false, updatable = false)
    private String tenantSurrogate;

    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "issue_type", length = 64, nullable = false, updatable = false)
    private String issueType;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_scope", length = 32, nullable = false, updatable = false)
    private ReconciliationScope issueScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16, nullable = false)
    private ReconciliationSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_status", length = 16, nullable = false)
    private ReconciliationStatus issueStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, updatable = false)
    private RoomType roomType;

    @Column(name = "room_epoch", nullable = false, updatable = false)
    private long roomEpoch;

    @Column(name = "process_revision", nullable = false, updatable = false)
    private long processRevision;

    @Column(name = "fencing_token", nullable = false, updatable = false)
    private long fencingToken;

    @Column(name = "expected_ref", length = 1024, updatable = false)
    private String expectedRef;

    @Column(name = "expected_sha256", length = 64, updatable = false)
    private String expectedSha256;

    @Column(name = "actual_ref", length = 1024, updatable = false)
    private String actualRef;

    @Column(name = "actual_sha256", length = 64, updatable = false)
    private String actualSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String detailsJson;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ProcessReconciliationIssueEntity() {}

    private ProcessReconciliationIssueEntity(String id) {
        super(id);
    }

    public static ProcessReconciliationIssueEntity detected(
            String id,
            String issueKey,
            String tenantSurrogate,
            String caseId,
            String issueType,
            ReconciliationScope issueScope,
            ReconciliationSeverity severity,
            RoomType roomType,
            long roomEpoch,
            long processRevision,
            long fencingToken,
            String expectedRef,
            String expectedSha256,
            String actualRef,
            String actualSha256,
            String detailsJson,
            OffsetDateTime detectedAt) {
        ProcessReconciliationIssueEntity entity =
                new ProcessReconciliationIssueEntity(id);
        entity.issueKey = Objects.requireNonNull(issueKey, "issueKey");
        entity.tenantSurrogate =
                Objects.requireNonNull(tenantSurrogate, "tenantSurrogate");
        entity.caseId = Objects.requireNonNull(caseId, "caseId");
        entity.issueType = Objects.requireNonNull(issueType, "issueType");
        entity.issueScope = Objects.requireNonNull(issueScope, "issueScope");
        entity.severity = Objects.requireNonNull(severity, "severity");
        entity.issueStatus = ReconciliationStatus.OPEN;
        entity.roomType = roomType;
        entity.roomEpoch = roomEpoch;
        entity.processRevision = processRevision;
        entity.fencingToken = fencingToken;
        entity.expectedRef = expectedRef;
        entity.expectedSha256 = expectedSha256;
        entity.actualRef = actualRef;
        entity.actualSha256 = actualSha256;
        entity.detailsJson = Objects.requireNonNull(detailsJson, "detailsJson");
        entity.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
        entity.createdAt = detectedAt;
        entity.updatedAt = detectedAt;
        return entity;
    }

    public String getIssueKey() {
        return issueKey;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getIssueType() {
        return issueType;
    }

    public ReconciliationScope getIssueScope() {
        return issueScope;
    }

    public ReconciliationSeverity getSeverity() {
        return severity;
    }

    public ReconciliationStatus getIssueStatus() {
        return issueStatus;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void markResolved(OffsetDateTime resolvedAt) {
        Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        if (issueStatus == ReconciliationStatus.RESOLVED) {
            return;
        }
        issueStatus = ReconciliationStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        updatedAt = resolvedAt;
    }

    public void reopenIfResolved(OffsetDateTime reopenedAt) {
        Objects.requireNonNull(reopenedAt, "reopenedAt must not be null");
        if (issueStatus != ReconciliationStatus.RESOLVED) {
            return;
        }
        issueStatus = ReconciliationStatus.OPEN;
        acknowledgedAt = null;
        resolvedAt = null;
        updatedAt = reopenedAt;
    }
}
