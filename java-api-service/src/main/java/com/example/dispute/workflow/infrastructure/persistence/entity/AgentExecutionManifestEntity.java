package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ManifestTerminalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "agent_execution_manifest")
public class AgentExecutionManifestEntity extends AbstractEntity {

    @Column(name = "schema_version", length = 128, nullable = false, updatable = false)
    private String schemaVersion;

    @Column(name = "tenant_surrogate", length = 128, nullable = false, updatable = false)
    private String tenantSurrogate;

    @Column(name = "case_id", length = 64, nullable = false, updatable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false, updatable = false)
    private RoomType roomType;

    @Column(name = "room_epoch", nullable = false, updatable = false)
    private long roomEpoch;

    @Column(name = "process_revision", nullable = false, updatable = false)
    private long processRevision;

    @Column(name = "fencing_token", nullable = false, updatable = false)
    private long fencingToken;

    @Column(name = "logical_agent_run_id", length = 128, nullable = false, updatable = false)
    private String logicalAgentRunId;

    @Column(name = "attempt_id", length = 128, updatable = false)
    private String attemptId;

    @Column(name = "workflow_id", length = 128, updatable = false)
    private String workflowId;

    @Column(name = "workflow_run_id", length = 128, updatable = false)
    private String workflowRunId;

    @Column(name = "workflow_type", length = 128, updatable = false)
    private String workflowType;

    @Column(name = "workflow_build_id", length = 128, updatable = false)
    private String workflowBuildId;

    @Column(name = "graph_key", length = 128, updatable = false)
    private String graphKey;

    @Column(name = "graph_version", length = 128, updatable = false)
    private String graphVersion;

    @Column(name = "checkpoint_schema_version", length = 128, updatable = false)
    private String checkpointSchemaVersion;

    @Column(name = "checkpoint_id", length = 128, updatable = false)
    private String checkpointId;

    @Column(name = "prompt_version", length = 128, updatable = false)
    private String promptVersion;

    @Column(name = "model_profile_id", length = 128, updatable = false)
    private String modelProfileId;

    @Column(name = "provider", length = 128, updatable = false)
    private String provider;

    @Column(name = "model_version", length = 128, updatable = false)
    private String modelVersion;

    @Column(name = "policy_version", length = 128, updatable = false)
    private String policyVersion;

    @Column(name = "guardrail_version", length = 128, updatable = false)
    private String guardrailVersion;

    @Column(name = "manifest_uri", length = 1024, nullable = false, updatable = false)
    private String manifestUri;

    @Column(name = "manifest_sha256", length = 64, updatable = false)
    private String manifestSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_refs_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String inputSnapshotRefsJson;

    @Column(name = "output_snapshot_id", length = 64, nullable = false, updatable = false)
    private String outputSnapshotId;

    @Column(name = "output_sha256", length = 64, nullable = false, updatable = false)
    private String outputSha256;

    @Column(name = "traceparent", length = 55, updatable = false)
    private String traceparent;

    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_status", length = 32, nullable = false, updatable = false)
    private ManifestTerminalStatus terminalStatus;

    @Column(name = "finalized_at", nullable = false, updatable = false)
    private OffsetDateTime finalizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AgentExecutionManifestEntity() {}

    public String getTenantSurrogate() {
        return tenantSurrogate;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getLogicalAgentRunId() {
        return logicalAgentRunId;
    }

    public String getManifestUri() {
        return manifestUri;
    }

    public String getOutputSnapshotId() {
        return outputSnapshotId;
    }

    public ManifestTerminalStatus getTerminalStatus() {
        return terminalStatus;
    }
}
