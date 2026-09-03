package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;

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
    @Column(name = "lifecycle_status", length = 24, nullable = false)
    private EpochLifecycleStatus lifecycleStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "provisioning_status", length = 24, nullable = false)
    private EpochProvisioningStatus provisioningStatus;

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

    @Column(name = "room_temporal_workflow_id", length = 128)
    private String roomTemporalWorkflowId;

    @Column(name = "room_temporal_run_id", length = 128)
    private String roomTemporalRunId;

    @Column(name = "temporal_build_id", length = 128, nullable = false, updatable = false)
    private String temporalBuildId;

    @Column(name = "graph_key", length = 128, nullable = false, updatable = false)
    private String graphKey;

    @Column(name = "graph_version", length = 128, nullable = false, updatable = false)
    private String graphVersion;

    @Column(name = "checkpoint_schema_version", length = 128, nullable = false, updatable = false)
    private String checkpointSchemaVersion;

    @Column(name = "stream_protocol", length = 64, nullable = false, updatable = false)
    private String streamProtocol;

    @Column(name = "selection_schema_version", length = 64, nullable = false, updatable = false)
    private String selectionSchemaVersion;

    @Column(name = "process_contract_version", length = 64, nullable = false, updatable = false)
    private String processContractVersion;

    @Column(name = "workflow_type", length = 128, nullable = false, updatable = false)
    private String workflowType;

    @Column(name = "room_workflow_type", length = 128, updatable = false)
    private String roomWorkflowType;

    @Column(name = "room_workflow_build_id", length = 128, updatable = false)
    private String roomWorkflowBuildId;

    @Column(name = "activated_at", nullable = false, updatable = false)
    private OffsetDateTime activatedAt;

    @Column(name = "terminal_at")
    private OffsetDateTime terminalAt;

    @Column(name = "provisioned_at")
    private OffsetDateTime provisionedAt;

    @Column(name = "provisioning_failure_code", length = 64)
    private String provisioningFailureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseRoomEpochEntity() {}

    private CaseRoomEpochEntity(String id) {
        super(id);
    }

    public static CaseRoomEpochEntity active(
            String id,
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            long roomEpoch,
            WriterMode writerMode,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol,
            String selectionSchemaVersion,
            String processContractVersion,
            String workflowType,
            OffsetDateTime activatedAt) {
        return active(
                id,
                tenantSurrogate,
                caseId,
                roomId,
                roomType,
                roomEpoch,
                writerMode,
                processRevision,
                roomRevision,
                fencingToken,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildId,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                streamProtocol,
                selectionSchemaVersion,
                processContractVersion,
                workflowType,
                null,
                null,
                activatedAt);
    }

    public static CaseRoomEpochEntity active(
            String id,
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            long roomEpoch,
            WriterMode writerMode,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol,
            String selectionSchemaVersion,
            String processContractVersion,
            String workflowType,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            OffsetDateTime activatedAt) {
        if (writerMode == WriterMode.TEMPORAL) {
            throw new IllegalArgumentException(
                    "TEMPORAL epochs must be created through the PREPARING lifecycle");
        }
        return create(
                id,
                tenantSurrogate,
                caseId,
                roomId,
                roomType,
                roomEpoch,
                writerMode,
                EpochLifecycleStatus.ACTIVE,
                processRevision,
                roomRevision,
                fencingToken,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildId,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                streamProtocol,
                selectionSchemaVersion,
                processContractVersion,
                workflowType,
                roomWorkflowType,
                roomWorkflowBuildId,
                activatedAt,
                null);
    }

    public static CaseRoomEpochEntity preparing(
            String id,
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            long roomEpoch,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String temporalWorkflowId,
            String temporalBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol,
            String selectionSchemaVersion,
            String processContractVersion,
            String workflowType,
            OffsetDateTime activatedAt) {
        return preparing(
                id,
                tenantSurrogate,
                caseId,
                roomId,
                roomType,
                roomEpoch,
                processRevision,
                roomRevision,
                fencingToken,
                temporalWorkflowId,
                temporalBuildId,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                streamProtocol,
                selectionSchemaVersion,
                processContractVersion,
                workflowType,
                null,
                null,
                activatedAt);
    }

    public static CaseRoomEpochEntity preparing(
            String id,
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            long roomEpoch,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String temporalWorkflowId,
            String temporalBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol,
            String selectionSchemaVersion,
            String processContractVersion,
            String workflowType,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            OffsetDateTime activatedAt) {
        return create(
                id,
                tenantSurrogate,
                caseId,
                roomId,
                roomType,
                roomEpoch,
                WriterMode.TEMPORAL,
                EpochLifecycleStatus.PREPARING,
                processRevision,
                roomRevision,
                fencingToken,
                temporalWorkflowId,
                null,
                temporalBuildId,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                streamProtocol,
                selectionSchemaVersion,
                processContractVersion,
                workflowType,
                roomWorkflowType,
                roomWorkflowBuildId,
                activatedAt,
                null);
    }

    public static CaseRoomEpochEntity terminal(
            String id,
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            long roomEpoch,
            WriterMode writerMode,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol,
            String selectionSchemaVersion,
            String processContractVersion,
            String workflowType,
            OffsetDateTime activatedAt,
            OffsetDateTime terminalAt) {
        return terminal(
                id,
                tenantSurrogate,
                caseId,
                roomId,
                roomType,
                roomEpoch,
                writerMode,
                processRevision,
                roomRevision,
                fencingToken,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildId,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                streamProtocol,
                selectionSchemaVersion,
                processContractVersion,
                workflowType,
                null,
                null,
                activatedAt,
                terminalAt);
    }

    public static CaseRoomEpochEntity terminal(
            String id,
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            long roomEpoch,
            WriterMode writerMode,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol,
            String selectionSchemaVersion,
            String processContractVersion,
            String workflowType,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            OffsetDateTime activatedAt,
            OffsetDateTime terminalAt) {
        if (writerMode != WriterMode.LEGACY) {
            throw new IllegalArgumentException(
                    "terminal imports must use the LEGACY writer mode");
        }
        return create(
                id,
                tenantSurrogate,
                caseId,
                roomId,
                roomType,
                roomEpoch,
                writerMode,
                EpochLifecycleStatus.TERMINAL,
                processRevision,
                roomRevision,
                fencingToken,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildId,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                streamProtocol,
                selectionSchemaVersion,
                processContractVersion,
                workflowType,
                roomWorkflowType,
                roomWorkflowBuildId,
                activatedAt,
                Objects.requireNonNull(terminalAt, "terminalAt must not be null"));
    }

    private static CaseRoomEpochEntity create(
            String id,
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            long roomEpoch,
            WriterMode writerMode,
            EpochLifecycleStatus lifecycleStatus,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String streamProtocol,
            String selectionSchemaVersion,
            String processContractVersion,
            String workflowType,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            OffsetDateTime activatedAt,
            OffsetDateTime terminalAt) {
        if (roomEpoch < 0 || processRevision < 0 || roomRevision < 0 || fencingToken < 0) {
            throw new IllegalArgumentException("epoch revisions and fencing token must be non-negative");
        }
        WriterMode requiredWriterMode = Objects.requireNonNull(writerMode, "writerMode must not be null");
        EpochLifecycleStatus requiredLifecycle =
                Objects.requireNonNull(lifecycleStatus, "lifecycleStatus must not be null");
        if (requiredWriterMode == WriterMode.LEGACY
                && (requiredLifecycle != EpochLifecycleStatus.ACTIVE
                        && requiredLifecycle != EpochLifecycleStatus.TERMINAL)) {
            throw new IllegalArgumentException("LEGACY epochs cannot use provisioning lifecycle states");
        }
        if (requiredWriterMode == WriterMode.LEGACY
                && (temporalWorkflowId != null || temporalRunId != null)) {
            throw new IllegalArgumentException("LEGACY epochs cannot have a Temporal binding");
        }
        if (requiredWriterMode == WriterMode.SHADOW
                && ((requiredLifecycle != EpochLifecycleStatus.ACTIVE
                                && requiredLifecycle != EpochLifecycleStatus.TERMINAL)
                        || isBlank(temporalWorkflowId)
                        || fencingToken < 1)) {
            throw new IllegalArgumentException("SHADOW epochs require a workflow and positive fence");
        }
        if (requiredWriterMode == WriterMode.TEMPORAL
                && (isBlank(temporalWorkflowId) || fencingToken < 1)) {
            throw new IllegalArgumentException("TEMPORAL epochs require a workflow and positive fence");
        }
        if (requiredWriterMode == WriterMode.TEMPORAL
                && requiredLifecycle == EpochLifecycleStatus.PREPARING
                && temporalRunId != null) {
            throw new IllegalArgumentException("PREPARING TEMPORAL epochs cannot bind a run");
        }
        if (requiredWriterMode == WriterMode.TEMPORAL
                && requiredLifecycle != EpochLifecycleStatus.PREPARING
                && (requiredLifecycle != EpochLifecycleStatus.ACTIVE
                        && requiredLifecycle != EpochLifecycleStatus.TERMINAL)) {
            throw new IllegalArgumentException("TEMPORAL epoch lifecycle is invalid at creation");
        }
        if (requiredWriterMode == WriterMode.TEMPORAL
                && requiredLifecycle != EpochLifecycleStatus.PREPARING
                && isBlank(temporalRunId)) {
            throw new IllegalArgumentException("active TEMPORAL epochs require a complete run binding");
        }
        CaseRoomEpochEntity entity = new CaseRoomEpochEntity(required(id, "id"));
        entity.tenantSurrogate = required(tenantSurrogate, "tenantSurrogate");
        entity.caseId = required(caseId, "caseId");
        entity.roomId = required(roomId, "roomId");
        entity.roomType = Objects.requireNonNull(roomType, "roomType must not be null");
        entity.roomEpoch = roomEpoch;
        entity.writerMode = requiredWriterMode;
        entity.lifecycleStatus = requiredLifecycle;
        entity.provisioningStatus =
                requiredWriterMode == WriterMode.LEGACY
                        ? EpochProvisioningStatus.NOT_REQUIRED
                        : EpochProvisioningStatus.PENDING;
        entity.processRevision = processRevision;
        entity.roomRevision = roomRevision;
        entity.fencingToken = fencingToken;
        entity.temporalWorkflowId = temporalWorkflowId;
        entity.temporalRunId = temporalRunId;
        entity.roomTemporalWorkflowId =
                requiredWriterMode == WriterMode.LEGACY
                        ? null
                        : CaseProcessWorkflowProtocol.roomWorkflowId(
                                entity.caseId, entity.roomType, entity.roomEpoch);
        entity.temporalBuildId = required(temporalBuildId, "temporalBuildId");
        entity.graphKey = required(graphKey, "graphKey");
        entity.graphVersion = required(graphVersion, "graphVersion");
        entity.checkpointSchemaVersion = required(checkpointSchemaVersion, "checkpointSchemaVersion");
        entity.streamProtocol = required(streamProtocol, "streamProtocol");
        entity.selectionSchemaVersion = required(selectionSchemaVersion, "selectionSchemaVersion");
        entity.processContractVersion = required(processContractVersion, "processContractVersion");
        entity.workflowType = required(workflowType, "workflowType");
        if ("room-epoch-selection.v1".equals(entity.selectionSchemaVersion)) {
            if (roomWorkflowType != null || roomWorkflowBuildId != null) {
                throw new IllegalArgumentException(
                        "v1 epoch cannot contain a room Workflow selection");
            }
        } else if ("room-epoch-selection.v2".equals(entity.selectionSchemaVersion)) {
            if (!CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE.equals(entity.workflowType)) {
                throw new IllegalArgumentException(
                        "v2 epoch requires the CaseProcessWorkflow case binding");
            }
            entity.roomWorkflowType = required(roomWorkflowType, "roomWorkflowType");
            entity.roomWorkflowBuildId = required(roomWorkflowBuildId, "roomWorkflowBuildId");
            if (requiredWriterMode == WriterMode.SHADOW
                    && (entity.roomType != RoomType.INTAKE
                            || !"IntakeRoomWorkflow".equals(entity.roomWorkflowType))) {
                throw new IllegalArgumentException(
                        "non-LEGACY v2 epochs require the IntakeRoomWorkflow binding");
            }
        } else {
            throw new IllegalArgumentException("unsupported selectionSchemaVersion");
        }
        entity.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        entity.terminalAt = terminalAt;
        entity.createdAt = activatedAt;
        entity.updatedAt = activatedAt;
        return entity;
    }

    public void terminalize(
            long expectedFencingToken,
            long newProcessRevision,
            long newRoomRevision,
            OffsetDateTime terminalAt) {
        if (lifecycleStatus != EpochLifecycleStatus.ACTIVE) {
            throw new IllegalStateException("only an ACTIVE room epoch can become terminal");
        }
        if (fencingToken != expectedFencingToken) {
            throw new IllegalStateException("room epoch fencing token is stale");
        }
        if (newProcessRevision <= processRevision || newRoomRevision < roomRevision) {
            throw new IllegalArgumentException("terminal revisions cannot move backward");
        }
        OffsetDateTime requiredTerminalAt =
                Objects.requireNonNull(terminalAt, "terminalAt must not be null");
        if (requiredTerminalAt.isBefore(activatedAt) || requiredTerminalAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("terminal time cannot move backward");
        }
        lifecycleStatus = EpochLifecycleStatus.TERMINAL;
        processRevision = newProcessRevision;
        roomRevision = newRoomRevision;
        this.terminalAt = requiredTerminalAt;
        updatedAt = requiredTerminalAt;
    }

    public void terminalizeFailedShadowProvisioning(
            long expectedFencingToken,
            long newProcessRevision,
            long newRoomRevision,
            String failureCode,
            OffsetDateTime failedAt) {
        if (writerMode != WriterMode.SHADOW
                || (provisioningStatus != EpochProvisioningStatus.PENDING
                        && provisioningStatus != EpochProvisioningStatus.PROVISIONING)) {
            throw new IllegalStateException(
                    "only pending SHADOW provisioning can fall back to LEGACY");
        }
        terminalize(
                expectedFencingToken,
                newProcessRevision,
                newRoomRevision,
                failedAt);
        provisioningStatus = EpochProvisioningStatus.FAILED;
        provisioningFailureCode = required(failureCode, "failureCode");
    }

    private static String required(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public String getCaseId() {
        return caseId;
    }

    public String getTenantSurrogate() {
        return tenantSurrogate;
    }

    public String getRoomId() {
        return roomId;
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

    public EpochProvisioningStatus getProvisioningStatus() {
        return provisioningStatus;
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

    public String getRoomTemporalWorkflowId() {
        return roomTemporalWorkflowId;
    }

    public String getRoomTemporalRunId() {
        return roomTemporalRunId;
    }

    public String getTemporalBuildId() {
        return temporalBuildId;
    }

    public String getGraphKey() {
        return graphKey;
    }

    public String getGraphVersion() {
        return graphVersion;
    }

    public String getCheckpointSchemaVersion() {
        return checkpointSchemaVersion;
    }

    public String getStreamProtocol() {
        return streamProtocol;
    }

    public String getSelectionSchemaVersion() {
        return selectionSchemaVersion;
    }

    public String getProcessContractVersion() {
        return processContractVersion;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public String getRoomWorkflowType() {
        return roomWorkflowType;
    }

    public String getRoomWorkflowBuildId() {
        return roomWorkflowBuildId;
    }

    public OffsetDateTime getActivatedAt() {
        return activatedAt;
    }

    public OffsetDateTime getTerminalAt() {
        return terminalAt;
    }

    public OffsetDateTime getProvisionedAt() {
        return provisionedAt;
    }

    public String getProvisioningFailureCode() {
        return provisioningFailureCode;
    }

    public long getVersion() {
        return version;
    }
}
