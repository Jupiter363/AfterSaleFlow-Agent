package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;

@Entity
@Table(name = "case_room_epoch")
public class CaseRoomEpochEntity extends AbstractEntity {

    @Column(name = "tenant_surrogate", length = 128, nullable = false, updatable = false)
    private String tenantSurrogate;

    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "room_id", length = 64, updatable = false)
    private String roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false, updatable = false)
    private RoomType roomType;

    @Column(name = "room_epoch", nullable = false, updatable = false)
    private long roomEpoch;

    @Enumerated(EnumType.STRING)
    @Column(name = "writer_mode", length = 16, nullable = false, updatable = false)
    private WriterMode writerMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", length = 16, nullable = false)
    private EpochLifecycleStatus lifecycleStatus;

    @Column(name = "process_revision", nullable = false)
    private long processRevision;

    @Column(name = "room_revision", nullable = false)
    private long roomRevision;

    @Column(name = "fencing_token", nullable = false, updatable = false)
    private long fencingToken;

    @Column(name = "temporal_workflow_id", length = 128, updatable = false)
    private String temporalWorkflowId;

    @Column(name = "temporal_run_id", length = 128)
    private String temporalRunId;

    @Column(name = "temporal_build_id", length = 128, updatable = false)
    private String temporalBuildId;

    @Column(name = "graph_key", length = 128, updatable = false)
    private String graphKey;

    @Column(name = "graph_version", length = 128, updatable = false)
    private String graphVersion;

    @Column(name = "checkpoint_schema_version", length = 128, updatable = false)
    private String checkpointSchemaVersion;

    @Column(name = "stream_protocol", length = 64, updatable = false)
    private String streamProtocol;

    @Column(name = "activated_at", nullable = false, updatable = false)
    private OffsetDateTime activatedAt;

    @Column(name = "terminal_at")
    private OffsetDateTime terminalAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseRoomEpochEntity() {}

    public String getCaseId() {
        return caseId;
    }

    public String getTenantSurrogate() {
        return tenantSurrogate;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public long getRoomEpoch() {
        return roomEpoch;
    }

    public WriterMode getWriterMode() {
        return writerMode;
    }

    public EpochLifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public long getProcessRevision() {
        return processRevision;
    }

    public long getRoomRevision() {
        return roomRevision;
    }

    public String getTemporalWorkflowId() {
        return temporalWorkflowId;
    }

    public String getTemporalRunId() {
        return temporalRunId;
    }

    public String getTemporalBuildId() {
        return temporalBuildId;
    }

    public long getVersion() {
        return version;
    }
}
