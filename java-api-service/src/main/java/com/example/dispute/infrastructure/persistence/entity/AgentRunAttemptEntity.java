package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "agent_run_attempt")
@AttributeOverride(
        name = "id",
        column = @Column(name = "id", length = 128, nullable = false, updatable = false))
public class AgentRunAttemptEntity extends AbstractEntity {

    @Column(name = "agent_run_id", length = 64, nullable = false, updatable = false)
    private String agentRunId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private long attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "attempt_status", length = 32, nullable = false)
    private AgentRunAttemptStatus attemptStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "executor_kind", length = 32, nullable = false, updatable = false)
    private AgentRunExecutorKind executorKind;

    @Column(name = "provider", length = 128)
    private String provider;

    @Column(name = "model_profile_id", length = 128)
    private String modelProfileId;

    @Column(name = "model_version", length = 128)
    private String modelVersion;

    @Column(name = "graph_key", length = 128)
    private String graphKey;

    @Column(name = "graph_version", length = 128)
    private String graphVersion;

    @Column(name = "checkpoint_schema_version", length = 128)
    private String checkpointSchemaVersion;

    @Column(name = "checkpoint_id", length = 128)
    private String checkpointId;

    @Column(name = "prompt_version", length = 128)
    private String promptVersion;

    @Column(name = "output_schema_version", length = 128)
    private String outputSchemaVersion;

    @Column(name = "policy_version", length = 128)
    private String policyVersion;

    @Column(name = "guardrail_version", length = 128)
    private String guardrailVersion;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "result_hash", length = 64)
    private String resultHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private String resultJson;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "error_retryable")
    private Boolean errorRetryable;

    @Column(name = "public_output_emitted", nullable = false)
    private boolean publicOutputEmitted;

    @Column(name = "final_frame_observed", nullable = false)
    private boolean finalFrameObserved;

    @Column(name = "last_sequence_no", nullable = false)
    private long lastSequenceNo;

    @Column(name = "last_heartbeat_at")
    private OffsetDateTime lastHeartbeatAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "attempt_version", nullable = false)
    private long attemptVersion;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected AgentRunAttemptEntity() {}

    private AgentRunAttemptEntity(String id) {
        super(id);
    }

    public static AgentRunAttemptEntity start(
            String agentRunId, ExecuteAgentRunRequest request, Instant startedAt) {
        requireEqual(agentRunId, request.agentRunId(), "agentRunId");
        RoomGraphCommand command = request.command();
        requireEqual(command.logicalRunId(), agentRunId, "logicalRunId");
        requireEqual(command.attemptId(), request.attemptId(), "attemptId");

        AgentRunAttemptEntity attempt = new AgentRunAttemptEntity(required(request.attemptId(), "attemptId"));
        attempt.agentRunId = required(agentRunId, "agentRunId");
        attempt.attemptNo = positive(request.attemptNo(), "attemptNo");
        attempt.attemptStatus = AgentRunAttemptStatus.RUNNING;
        attempt.executorKind = AgentRunExecutorKind.TEMPORAL_ACTIVITY;
        attempt.modelProfileId = required(command.invocationContext().modelProfileId(), "modelProfileId");
        attempt.graphKey = required(command.graphKey(), "graphKey");
        attempt.graphVersion = required(command.graphVersion(), "graphVersion");
        attempt.checkpointSchemaVersion =
                required(command.checkpointSchemaVersion(), "checkpointSchemaVersion");
        attempt.promptVersion = required(command.invocationContext().promptProfileId(), "promptProfileId");
        attempt.outputSchemaVersion =
                required(command.invocationContext().outputSchemaVersion(), "outputSchemaVersion");
        attempt.policyVersion = required(command.invocationContext().policyVersion(), "policyVersion");
        attempt.guardrailVersion =
                required(command.invocationContext().guardrailVersion(), "guardrailVersion");
        attempt.requestHash = sha256(command.requestHash(), "requestHash");
        attempt.startedAt = at(startedAt, "startedAt");
        attempt.lastHeartbeatAt = attempt.startedAt;
        attempt.createdAt = attempt.startedAt;
        attempt.updatedAt = attempt.startedAt;
        attempt.createdBy = "temporal-agent-activity";
        return attempt;
    }

    public void requireSameRequest(ExecuteAgentRunRequest request) {
        requireEqual(agentRunId, request.agentRunId(), "agentRunId");
        requireEqual(getId(), request.attemptId(), "attemptId");
        requireEqual(attemptNo, request.attemptNo(), "attemptNo");
        requireEqual(graphKey, request.command().graphKey(), "graphKey");
        requireEqual(graphVersion, request.command().graphVersion(), "graphVersion");
        requireEqual(requestHash, request.command().requestHash(), "requestHash");
        requireEqual(
                modelProfileId,
                request.command().invocationContext().modelProfileId(),
                "modelProfileId");
    }

    public void recordHeartbeat(AgentRunAttemptHeartbeat heartbeat) {
        requireIdentity(heartbeat.agentRunId(), heartbeat.attemptId(), heartbeat.attemptNo());
        if (attemptStatus != AgentRunAttemptStatus.RUNNING) {
            if (heartbeat.lastSequenceNo() <= lastSequenceNo
                    && (!heartbeat.publicOutputEmitted() || publicOutputEmitted)
                    && (!heartbeat.finalFrameObserved() || finalFrameObserved)) {
                return;
            }
            throw new IllegalStateException("terminal attempt progress cannot advance");
        }
        if (heartbeat.lastSequenceNo() < lastSequenceNo
                || heartbeat.recordedAt().isBefore(lastHeartbeatAt.toInstant())) {
            return;
        }
        lastSequenceNo = heartbeat.lastSequenceNo();
        publicOutputEmitted |= heartbeat.publicOutputEmitted();
        finalFrameObserved |= heartbeat.finalFrameObserved();
        lastHeartbeatAt = at(heartbeat.recordedAt(), "recordedAt");
        updatedAt = lastHeartbeatAt;
    }

    public void recordResultReady(ExecuteAgentRunResult result, String serializedResult) {
        requireIdentity(result.agentRunId(), result.attemptId(), result.attemptNo());
        if (result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED) {
            throw new IllegalArgumentException("only a completed result can become RESULT_READY");
        }
        if (attemptStatus == AgentRunAttemptStatus.RESULT_READY
                || attemptStatus == AgentRunAttemptStatus.COMPLETED) {
            requireEqual(resultHash, result.resultHash(), "resultHash");
            return;
        }
        if (attemptStatus != AgentRunAttemptStatus.RUNNING) {
            throw new IllegalStateException("attempt cannot become result-ready from " + attemptStatus);
        }

        RoomGraphResult graphResult = result.graphResult();
        requireEqual(graphKey, graphResult.graphKey(), "graphKey");
        requireEqual(graphVersion, graphResult.graphVersion(), "graphVersion");
        checkpointId = required(graphResult.checkpointId(), "checkpointId");
        promptVersion = required(graphResult.executionMetadata().promptVersion(), "promptVersion");
        modelProfileId = required(graphResult.executionMetadata().modelProfileId(), "modelProfileId");
        outputSchemaVersion = required(graphResult.executionMetadata().schemaVersion(), "schemaVersion");
        policyVersion = required(graphResult.executionMetadata().policyVersion(), "policyVersion");
        guardrailVersion = required(graphResult.executionMetadata().guardrailVersion(), "guardrailVersion");
        resultHash = sha256(result.resultHash(), "resultHash");
        resultJson = required(serializedResult, "serializedResult");
        inputTokens = graphResult.usage().inputTokens();
        outputTokens = graphResult.usage().outputTokens();
        totalTokens = graphResult.usage().totalTokens();
        completedAt = at(result.completedAt(), "completedAt");
        latencyMs = Math.max(0, Duration.between(startedAt, completedAt).toMillis());
        lastSequenceNo = Math.max(lastSequenceNo, result.lastSequenceNo());
        publicOutputEmitted |= result.publicOutputEmitted();
        finalFrameObserved = true;
        attemptStatus = AgentRunAttemptStatus.RESULT_READY;
        updatedAt = completedAt;
    }

    public void recordFailure(
            AgentRunAttemptStatus status,
            String errorCode,
            boolean retryable,
            Instant failedAt) {
        if (status != AgentRunAttemptStatus.FAILED
                && status != AgentRunAttemptStatus.ABORTED
                && status != AgentRunAttemptStatus.CANCELLED) {
            throw new IllegalArgumentException("status must be a terminal attempt failure");
        }
        if (attemptStatus == status) {
            requireEqual(this.errorCode, errorCode, "errorCode");
            requireEqual(this.errorRetryable, retryable, "errorRetryable");
            return;
        }
        if (attemptStatus != AgentRunAttemptStatus.RUNNING) {
            throw new IllegalStateException("attempt cannot fail from " + attemptStatus);
        }
        attemptStatus = status;
        this.errorCode = required(errorCode, "errorCode");
        errorRetryable = retryable;
        completedAt = at(failedAt, "failedAt");
        latencyMs = Math.max(0, Duration.between(startedAt, completedAt).toMillis());
        updatedAt = completedAt;
    }

    public void markCommitted(AgentExecutionManifest manifest) {
        requireEqual(getId(), manifest.agentRun().attemptId(), "attemptId");
        if (attemptStatus == AgentRunAttemptStatus.COMPLETED) {
            requireEqual(provider, manifest.model().provider(), "provider");
            requireEqual(modelVersion, manifest.model().model(), "modelVersion");
            return;
        }
        if (attemptStatus != AgentRunAttemptStatus.RESULT_READY) {
            throw new IllegalStateException("attempt result is not ready for commit");
        }
        provider = required(manifest.model().provider(), "provider");
        modelVersion = required(manifest.model().model(), "modelVersion");
        checkpointId = required(manifest.graph().checkpointId(), "checkpointId");
        promptVersion = required(manifest.model().promptVersion(), "promptVersion");
        modelProfileId = required(manifest.model().modelProfileId(), "modelProfileId");
        inputTokens = manifest.usage().inputTokens();
        outputTokens = manifest.usage().outputTokens();
        totalTokens = manifest.usage().totalTokens();
        latencyMs = manifest.usage().latencyMs();
        attemptStatus = AgentRunAttemptStatus.COMPLETED;
        updatedAt = at(manifest.finalizedAt(), "finalizedAt");
    }

    private void requireIdentity(String runId, String attemptId, long number) {
        requireEqual(agentRunId, runId, "agentRunId");
        requireEqual(getId(), attemptId, "attemptId");
        requireEqual(attemptNo, number, "attemptNo");
    }

    public String getAgentRunId() {
        return agentRunId;
    }

    public long getAttemptNo() {
        return attemptNo;
    }

    public AgentRunAttemptStatus getAttemptStatus() {
        return attemptStatus;
    }

    public AgentRunExecutorKind getExecutorKind() {
        return executorKind;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelProfileId() {
        return modelProfileId;
    }

    public String getModelVersion() {
        return modelVersion;
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

    public String getCheckpointId() {
        return checkpointId;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getOutputSchemaVersion() {
        return outputSchemaVersion;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public String getGuardrailVersion() {
        return guardrailVersion;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResultHash() {
        return resultHash;
    }

    public String getResultJson() {
        return resultJson;
    }

    public Long getInputTokens() {
        return inputTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Boolean getErrorRetryable() {
        return errorRetryable;
    }

    public boolean isPublicOutputEmitted() {
        return publicOutputEmitted;
    }

    public boolean isFinalFrameObserved() {
        return finalFrameObserved;
    }

    public long getLastSequenceNo() {
        return lastSequenceNo;
    }

    public OffsetDateTime getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public long getAttemptVersion() {
        return attemptVersion;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static long positive(long value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static OffsetDateTime at(Instant value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value.atOffset(ZoneOffset.UTC);
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalStateException(field + " conflicts with the persisted attempt");
        }
    }
}
