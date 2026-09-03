package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.BootstrapOutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "room_epoch_bootstrap_outbox")
public class RoomEpochBootstrapOutboxEntity extends AbstractEntity {

    @Column(name = "epoch_id", length = 64, nullable = false, updatable = false)
    private String epochId;

    @Column(name = "tenant_surrogate", length = 128, nullable = false, updatable = false)
    private String tenantSurrogate;

    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false, updatable = false)
    private RoomType roomType;

    @Column(name = "room_epoch", nullable = false, updatable = false)
    private long roomEpoch;

    @Column(name = "fencing_token", nullable = false, updatable = false)
    private long fencingToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "writer_mode", length = 16, nullable = false, updatable = false)
    private WriterMode writerMode;

    @Column(name = "case_workflow_id", length = 128, nullable = false, updatable = false)
    private String caseWorkflowId;

    @Column(name = "room_workflow_id", length = 128, nullable = false, updatable = false)
    private String roomWorkflowId;

    @Column(name = "workflow_type", length = 128, nullable = false, updatable = false)
    private String workflowType;

    @Column(name = "task_queue", length = 128, nullable = false, updatable = false)
    private String taskQueue;

    @Column(name = "update_id", length = 128, nullable = false, updatable = false)
    private String updateId;

    @Column(name = "payload_json", columnDefinition = "text", nullable = false, updatable = false)
    private String payloadJson;

    @Column(name = "payload_sha256", length = 64, nullable = false, updatable = false)
    private String payloadSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "outbox_status", length = 32, nullable = false)
    private BootstrapOutboxStatus outboxStatus;

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

    @Column(name = "case_temporal_run_id", length = 128)
    private String caseTemporalRunId;

    @Column(name = "room_temporal_run_id", length = 128)
    private String roomTemporalRunId;

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

    protected RoomEpochBootstrapOutboxEntity() {}

    private RoomEpochBootstrapOutboxEntity(String id) {
        super(id);
    }

    public static RoomEpochBootstrapOutboxEntity pending(
            String id,
            ProvisionRoomEpoch command,
            String workflowType,
            String taskQueue,
            String payloadJson,
            String payloadSha256,
            OffsetDateTime availableAt) {
        Objects.requireNonNull(command, "command must not be null");
        RoomEpochBootstrapOutboxEntity entity =
                new RoomEpochBootstrapOutboxEntity(required(id, "id"));
        entity.epochId = command.epochId();
        entity.tenantSurrogate = command.tenantSurrogate();
        entity.caseId = command.caseId();
        entity.roomType = command.roomType();
        entity.roomEpoch = command.roomEpoch();
        entity.fencingToken = command.fencingToken();
        entity.writerMode = command.writerMode();
        entity.caseWorkflowId = command.caseWorkflowId();
        entity.roomWorkflowId = command.roomWorkflowId();
        entity.workflowType = required(workflowType, "workflowType");
        entity.taskQueue = required(taskQueue, "taskQueue");
        entity.updateId = command.updateId();
        entity.payloadJson = required(payloadJson, "payloadJson");
        entity.payloadSha256 = required(payloadSha256, "payloadSha256");
        entity.outboxStatus = BootstrapOutboxStatus.PENDING;
        entity.availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
        entity.createdAt = availableAt;
        entity.updatedAt = availableAt;
        return entity;
    }

    public void claim(String leaseToken, OffsetDateTime claimedAt, OffsetDateTime leaseUntil) {
        required(leaseToken, "leaseToken");
        Objects.requireNonNull(claimedAt, "claimedAt must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        if (!leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException("leaseUntil must be after claimedAt");
        }
        boolean available =
                (outboxStatus == BootstrapOutboxStatus.PENDING
                                || outboxStatus == BootstrapOutboxStatus.RETRY)
                        && !availableAt.isAfter(claimedAt);
        boolean expired =
                outboxStatus == BootstrapOutboxStatus.CLAIMED
                        && leaseExpiresAt != null
                        && !leaseExpiresAt.isAfter(claimedAt);
        if (!available && !expired) {
            throw new IllegalStateException("bootstrap outbox row is not claimable");
        }
        outboxStatus = BootstrapOutboxStatus.CLAIMED;
        attemptCount = Math.incrementExact(attemptCount);
        leaseOwner = leaseToken;
        leaseExpiresAt = leaseUntil;
        lastAttemptAt = claimedAt;
        updatedAt = claimedAt;
    }

    public String getEpochId() { return epochId; }
    public String getTenantSurrogate() { return tenantSurrogate; }
    public String getCaseId() { return caseId; }
    public RoomType getRoomType() { return roomType; }
    public long getRoomEpoch() { return roomEpoch; }
    public long getFencingToken() { return fencingToken; }
    public WriterMode getWriterMode() { return writerMode; }
    public String getCaseWorkflowId() { return caseWorkflowId; }
    public String getRoomWorkflowId() { return roomWorkflowId; }
    public String getWorkflowType() { return workflowType; }
    public String getTaskQueue() { return taskQueue; }
    public String getUpdateId() { return updateId; }
    public String getPayloadJson() { return payloadJson; }
    public String getPayloadSha256() { return payloadSha256; }
    public BootstrapOutboxStatus getOutboxStatus() { return outboxStatus; }
    public OffsetDateTime getAvailableAt() { return availableAt; }
    public int getAttemptCount() { return attemptCount; }
    public String getLeaseOwner() { return leaseOwner; }
    public OffsetDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public String getLastErrorDetail() { return lastErrorDetail; }
    public OffsetDateTime getDeliveredAt() { return deliveredAt; }
    public String getCaseTemporalRunId() { return caseTemporalRunId; }
    public String getRoomTemporalRunId() { return roomTemporalRunId; }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
