package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.DeliveryKind;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;

@Entity
@Table(name = "case_command_outbox")
public class CaseCommandOutboxEntity extends AbstractEntity {

    @Column(name = "case_command_id", length = 64, nullable = false, updatable = false)
    private String caseCommandId;

    @Column(name = "tenant_surrogate", length = 128, nullable = false, updatable = false)
    private String tenantSurrogate;

    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "workflow_id", length = 128, nullable = false, updatable = false)
    private String workflowId;

    @Column(name = "workflow_type", length = 128, nullable = false, updatable = false)
    private String workflowType;

    @Column(name = "task_queue", length = 128, nullable = false, updatable = false)
    private String taskQueue;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_kind", length = 32, nullable = false, updatable = false)
    private DeliveryKind deliveryKind;

    @Column(name = "update_id", length = 128, nullable = false, updatable = false)
    private String updateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outbox_status", length = 32, nullable = false)
    private OutboxStatus outboxStatus;

    @Column(name = "available_at", nullable = false)
    private OffsetDateTime availableAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "temporal_run_id", length = 128)
    private String temporalRunId;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_detail", columnDefinition = "text")
    private String lastErrorDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseCommandOutboxEntity() {}

    public String getCaseCommandId() {
        return caseCommandId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getUpdateId() {
        return updateId;
    }

    public OutboxStatus getOutboxStatus() {
        return outboxStatus;
    }

    public OffsetDateTime getAvailableAt() {
        return availableAt;
    }
}
