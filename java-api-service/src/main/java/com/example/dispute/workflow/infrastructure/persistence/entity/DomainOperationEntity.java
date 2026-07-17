package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OperationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;

@Entity
@Table(name = "domain_operation")
public class DomainOperationEntity extends AbstractEntity {

    @Column(name = "operation_key", length = 128, nullable = false, updatable = false)
    private String operationKey;

    @Column(name = "tenant_surrogate", length = 128, nullable = false, updatable = false)
    private String tenantSurrogate;

    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "case_command_id", length = 64, updatable = false)
    private String caseCommandId;

    @Column(name = "operation_type", length = 64, nullable = false, updatable = false)
    private String operationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, updatable = false)
    private RoomType roomType;

    @Column(name = "room_epoch", nullable = false, updatable = false)
    private long roomEpoch;

    @Column(name = "process_revision", nullable = false, updatable = false)
    private long processRevision;

    @Column(name = "fencing_token", nullable = false, updatable = false)
    private long fencingToken;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_status", length = 32, nullable = false)
    private OperationStatus operationStatus;

    @Column(name = "result_uri", length = 1024)
    private String resultUri;

    @Column(name = "result_sha256", length = 64)
    private String resultSha256;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_detail", columnDefinition = "text")
    private String failureDetail;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DomainOperationEntity() {}

    public String getOperationKey() {
        return operationKey;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public OperationStatus getOperationStatus() {
        return operationStatus;
    }

    public String getResultUri() {
        return resultUri;
    }
}
