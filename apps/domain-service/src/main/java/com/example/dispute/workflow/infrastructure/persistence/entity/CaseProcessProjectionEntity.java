package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.WriterActivationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "case_process_projection")
public class CaseProcessProjectionEntity {

    @Id
    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "tenant_surrogate", length = 128, nullable = false)
    private String tenantSurrogate;

    @Column(name = "macro_phase", length = 64, nullable = false)
    private String macroPhase;

    @Column(name = "current_room", length = 32)
    private String currentRoom;

    @Column(name = "room_phase", length = 64, nullable = false)
    private String roomPhase;

    @Enumerated(EnumType.STRING)
    @Column(name = "writer_mode", length = 16, nullable = false)
    private WriterMode writerMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "writer_activation_status", length = 24, nullable = false)
    private WriterActivationStatus writerActivationStatus;

    @Column(name = "process_revision", nullable = false)
    private long processRevision;

    @Column(name = "room_epoch", nullable = false)
    private long roomEpoch;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "last_command_sequence", nullable = false)
    private long lastCommandSequence;

    @Column(name = "last_case_event_sequence", nullable = false)
    private long lastCaseEventSequence;

    @Column(name = "projected_deadline_at")
    private OffsetDateTime projectedDeadlineAt;

    @Column(name = "temporal_workflow_id", length = 128)
    private String temporalWorkflowId;

    @Column(name = "temporal_run_id", length = 128)
    private String temporalRunId;

    @Column(name = "temporal_build_id", length = 128, nullable = false)
    private String temporalBuildId;

    @Column(name = "projection_ref", length = 1024)
    private String projectionRef;

    @Column(name = "projection_sha256", length = 64)
    private String projectionSha256;

    @Column(name = "projected_at", nullable = false)
    private OffsetDateTime projectedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseProcessProjectionEntity() {}

    public static CaseProcessProjectionEntity initialize(
            String caseId,
            String tenantSurrogate,
            String macroPhase,
            String currentRoom,
            String roomPhase,
            WriterMode writerMode,
            long processRevision,
            long roomEpoch,
            long fencingToken,
            OffsetDateTime projectedDeadlineAt,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            OffsetDateTime projectedAt) {
        validateRevisions(processRevision, roomEpoch, fencingToken);
        validateBinding(writerMode, fencingToken, temporalWorkflowId, temporalRunId);
        CaseProcessProjectionEntity entity = new CaseProcessProjectionEntity();
        entity.caseId = required(caseId, "caseId");
        entity.tenantSurrogate = required(tenantSurrogate, "tenantSurrogate");
        entity.macroPhase = required(macroPhase, "macroPhase");
        entity.currentRoom = currentRoom;
        entity.roomPhase = required(roomPhase, "roomPhase");
        entity.writerMode = Objects.requireNonNull(writerMode, "writerMode must not be null");
        entity.writerActivationStatus =
                writerMode == WriterMode.LEGACY
                        ? currentRoom == null
                                ? WriterActivationStatus.TERMINAL
                                : WriterActivationStatus.READY
                        : WriterActivationStatus.PREPARING;
        entity.processRevision = processRevision;
        entity.roomEpoch = roomEpoch;
        entity.fencingToken = fencingToken;
        entity.lastCommandSequence = 0;
        entity.lastCaseEventSequence = 0;
        entity.projectedDeadlineAt = projectedDeadlineAt;
        entity.temporalWorkflowId = temporalWorkflowId;
        entity.temporalRunId = temporalRunId;
        entity.temporalBuildId = required(temporalBuildId, "temporalBuildId");
        entity.projectedAt = Objects.requireNonNull(projectedAt, "projectedAt must not be null");
        entity.updatedAt = projectedAt;
        return entity;
    }

    public void switchTo(
            long expectedRoomEpoch,
            long expectedFencingToken,
            String macroPhase,
            String currentRoom,
            String roomPhase,
            WriterMode writerMode,
            long newProcessRevision,
            long newRoomEpoch,
            long newFencingToken,
            OffsetDateTime projectedDeadlineAt,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            OffsetDateTime projectedAt) {
        switchToInternal(
                expectedRoomEpoch,
                expectedFencingToken,
                macroPhase,
                currentRoom,
                roomPhase,
                writerMode,
                newProcessRevision,
                newRoomEpoch,
                newFencingToken,
                projectedDeadlineAt,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildId,
                projectedAt,
                false,
                null,
                null);
    }

    public void advanceSequenceHighWater(
            long expectedRoomEpoch,
            long expectedFencingToken,
            long newLastCommandSequence,
            long newLastCaseEventSequence) {
        requireExpectedTuple(expectedRoomEpoch, expectedFencingToken);
        if (newLastCommandSequence < lastCommandSequence
                || newLastCaseEventSequence < lastCaseEventSequence) {
            throw new IllegalArgumentException(
                    "process projection sequence high-water marks cannot move backward");
        }
        lastCommandSequence = newLastCommandSequence;
        lastCaseEventSequence = newLastCaseEventSequence;
    }

    public void switchTo(
            long expectedRoomEpoch,
            long expectedFencingToken,
            String macroPhase,
            String currentRoom,
            String roomPhase,
            WriterMode writerMode,
            long newProcessRevision,
            long newRoomEpoch,
            long newFencingToken,
            OffsetDateTime projectedDeadlineAt,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            OffsetDateTime projectedAt,
            String projectionRef,
            String projectionSha256) {
        validateProjectionPair(projectionRef, projectionSha256);
        switchToInternal(
                expectedRoomEpoch,
                expectedFencingToken,
                macroPhase,
                currentRoom,
                roomPhase,
                writerMode,
                newProcessRevision,
                newRoomEpoch,
                newFencingToken,
                projectedDeadlineAt,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildId,
                projectedAt,
                true,
                projectionRef,
                projectionSha256);
    }

    private void switchToInternal(
            long expectedRoomEpoch,
            long expectedFencingToken,
            String macroPhase,
            String currentRoom,
            String roomPhase,
            WriterMode writerMode,
            long newProcessRevision,
            long newRoomEpoch,
            long newFencingToken,
            OffsetDateTime projectedDeadlineAt,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildId,
            OffsetDateTime projectedAt,
            boolean replaceProjectionPair,
            String projectionRef,
            String projectionSha256) {
        requireExpectedTuple(expectedRoomEpoch, expectedFencingToken);
        if (newProcessRevision <= processRevision) {
            throw new IllegalArgumentException("process revision must advance during an epoch switch");
        }
        OffsetDateTime nextProjectedAt = requireMonotonicTimestamp(projectedAt);
        validateRevisions(newProcessRevision, newRoomEpoch, newFencingToken);
        validateBinding(writerMode, newFencingToken, temporalWorkflowId, temporalRunId);
        this.macroPhase = required(macroPhase, "macroPhase");
        this.currentRoom = required(currentRoom, "currentRoom");
        this.roomPhase = required(roomPhase, "roomPhase");
        this.writerMode = Objects.requireNonNull(writerMode, "writerMode must not be null");
        writerActivationStatus =
                writerMode == WriterMode.LEGACY
                        ? WriterActivationStatus.READY
                        : WriterActivationStatus.PREPARING;
        processRevision = newProcessRevision;
        roomEpoch = newRoomEpoch;
        fencingToken = newFencingToken;
        this.projectedDeadlineAt = projectedDeadlineAt;
        this.temporalWorkflowId = temporalWorkflowId;
        this.temporalRunId = temporalRunId;
        this.temporalBuildId = required(temporalBuildId, "temporalBuildId");
        if (replaceProjectionPair) {
            this.projectionRef = projectionRef;
            this.projectionSha256 = projectionSha256;
        }
        this.projectedAt = nextProjectedAt;
        updatedAt = nextProjectedAt;
    }

    public void terminate(
            long expectedRoomEpoch,
            long expectedFencingToken,
            String macroPhase,
            String roomPhase,
            long newProcessRevision,
            OffsetDateTime projectedAt) {
        requireExpectedTuple(expectedRoomEpoch, expectedFencingToken);
        if (newProcessRevision <= processRevision) {
            throw new IllegalArgumentException("process revision must advance during termination");
        }
        OffsetDateTime nextProjectedAt = requireMonotonicTimestamp(projectedAt);
        this.macroPhase = required(macroPhase, "macroPhase");
        currentRoom = null;
        this.roomPhase = required(roomPhase, "roomPhase");
        writerActivationStatus = WriterActivationStatus.TERMINAL;
        processRevision = newProcessRevision;
        projectedDeadlineAt = null;
        this.projectedAt = nextProjectedAt;
        updatedAt = nextProjectedAt;
    }

    private OffsetDateTime requireMonotonicTimestamp(OffsetDateTime candidate) {
        OffsetDateTime required =
                Objects.requireNonNull(candidate, "projectedAt must not be null");
        if (required.isBefore(projectedAt) || required.isBefore(updatedAt)) {
            throw new IllegalArgumentException("projection time cannot move backward");
        }
        return required;
    }

    private void requireExpectedTuple(long expectedRoomEpoch, long expectedFencingToken) {
        if (roomEpoch != expectedRoomEpoch || fencingToken != expectedFencingToken) {
            throw new IllegalStateException("process projection room epoch or fence is stale");
        }
    }

    private static void validateRevisions(
            long processRevision, long roomEpoch, long fencingToken) {
        if (processRevision < 0 || roomEpoch < 0 || fencingToken < 0) {
            throw new IllegalArgumentException("projection revisions and fence must be non-negative");
        }
    }

    private static void validateBinding(
            WriterMode writerMode,
            long fencingToken,
            String temporalWorkflowId,
            String temporalRunId) {
        WriterMode requiredWriter = Objects.requireNonNull(writerMode, "writerMode must not be null");
        if (requiredWriter == WriterMode.LEGACY
                && (temporalWorkflowId != null || temporalRunId != null)) {
            throw new IllegalArgumentException("LEGACY projections cannot have a Temporal binding");
        }
        if (requiredWriter == WriterMode.SHADOW
                && (isBlank(temporalWorkflowId) || fencingToken < 1)) {
            throw new IllegalArgumentException("SHADOW projections require a workflow and positive fence");
        }
        if (requiredWriter == WriterMode.TEMPORAL
                && (isBlank(temporalWorkflowId) || fencingToken < 1)) {
            throw new IllegalArgumentException(
                    "TEMPORAL projections require a workflow and positive fence");
        }
    }

    private static void validateProjectionPair(String projectionRef, String projectionSha256) {
        if ((projectionRef == null) != (projectionSha256 == null)) {
            throw new IllegalArgumentException(
                    "projectionRef and projectionSha256 must both be absent or present");
        }
        if (projectionRef == null) {
            return;
        }
        if (projectionRef.isBlank() || projectionRef.length() > 1024) {
            throw new IllegalArgumentException("projectionRef is invalid");
        }
        if (!projectionSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("projectionSha256 must be lowercase SHA-256");
        }
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

    public String getCurrentRoom() {
        return currentRoom;
    }

    public String getMacroPhase() {
        return macroPhase;
    }

    public String getRoomPhase() {
        return roomPhase;
    }

    public WriterMode getWriterMode() {
        return writerMode;
    }

    public WriterActivationStatus getWriterActivationStatus() {
        return writerActivationStatus;
    }

    public long getProcessRevision() {
        return processRevision;
    }

    public long getRoomEpoch() {
        return roomEpoch;
    }

    public long getFencingToken() {
        return fencingToken;
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

    public long getLastCommandSequence() {
        return lastCommandSequence;
    }

    public long getLastCaseEventSequence() {
        return lastCaseEventSequence;
    }

    public OffsetDateTime getProjectedDeadlineAt() {
        return projectedDeadlineAt;
    }

    public String getProjectionRef() {
        return projectionRef;
    }

    public String getProjectionSha256() {
        return projectionSha256;
    }

    public long getVersion() {
        return version;
    }
}
