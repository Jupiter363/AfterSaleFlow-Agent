package com.example.dispute.workflow.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
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

    public static AgentExecutionManifestEntity formal(
            AgentExecutionManifest manifest,
            RoomType roomType,
            String manifestUri,
            String manifestHash,
            String inputSnapshotRefsJson) {
        AgentExecutionManifestEntity entity =
                new AgentExecutionManifestEntity(manifest.manifestId());
        entity.schemaVersion = manifest.schemaVersion();
        entity.tenantSurrogate = manifest.tenantSurrogate();
        entity.caseId = manifest.caseId();
        entity.roomType = required(roomType, "roomType");
        entity.roomEpoch = nonNegative(manifest.roomEpoch(), "roomEpoch");
        entity.processRevision = nonNegative(manifest.processRevision(), "processRevision");
        entity.fencingToken = positive(manifest.fencingToken(), "fencingToken");
        entity.logicalAgentRunId = required(manifest.agentRun().logicalRunId(), "logicalRunId");
        entity.attemptId = required(manifest.agentRun().attemptId(), "attemptId");
        entity.workflowId = manifest.workflow().workflowId();
        entity.workflowRunId = manifest.workflow().runId();
        entity.workflowType = manifest.workflow().workflowType();
        entity.workflowBuildId = manifest.workflow().buildId();
        entity.graphKey = manifest.graph().graphKey();
        entity.graphVersion = manifest.graph().graphVersion();
        entity.checkpointSchemaVersion = manifest.graph().checkpointSchemaVersion();
        entity.checkpointId = manifest.graph().checkpointId();
        entity.promptVersion = manifest.model().promptVersion();
        entity.modelProfileId = manifest.model().modelProfileId();
        entity.provider = manifest.model().provider();
        entity.modelVersion = manifest.model().model();
        entity.policyVersion = manifest.policyVersion();
        entity.guardrailVersion = manifest.guardrailVersion();
        entity.manifestUri = required(manifestUri, "manifestUri");
        entity.manifestSha256 = sha256(manifestHash, "manifestHash");
        entity.inputSnapshotRefsJson = required(inputSnapshotRefsJson, "inputSnapshotRefsJson");
        entity.outputSnapshotId = required(manifest.output().artifactId(), "outputSnapshotId");
        entity.outputSha256 = sha256(manifest.output().sha256(), "outputSha256");
        entity.traceparent = required(manifest.traceparent(), "traceparent");
        entity.terminalStatus = ManifestTerminalStatus.COMPLETED;
        entity.finalizedAt = manifest.finalizedAt().atOffset(java.time.ZoneOffset.UTC);
        entity.createdAt = entity.finalizedAt;
        return entity;
    }

    private AgentExecutionManifestEntity(String id) {
        super(required(id, "manifestId"));
    }

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

    public String getAttemptId() {
        return attemptId;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public String getManifestSha256() {
        return manifestSha256;
    }

    public String getOutputSnapshotId() {
        return outputSnapshotId;
    }

    public String getOutputSha256() {
        return outputSha256;
    }

    public ManifestTerminalStatus getTerminalStatus() {
        return terminalStatus;
    }

    public OffsetDateTime getFinalizedAt() {
        return finalizedAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static long nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static long positive(long value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
