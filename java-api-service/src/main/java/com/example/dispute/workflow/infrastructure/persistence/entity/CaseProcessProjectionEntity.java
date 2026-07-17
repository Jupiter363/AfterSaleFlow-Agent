package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;

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

    @Column(name = "temporal_build_id", length = 128)
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

    public String getCaseId() {
        return caseId;
    }

    public String getTenantSurrogate() {
        return tenantSurrogate;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public WriterMode getWriterMode() {
        return writerMode;
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
}
