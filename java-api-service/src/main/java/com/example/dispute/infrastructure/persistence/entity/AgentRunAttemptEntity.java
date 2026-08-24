package com.example.dispute.infrastructure.persistence.entity;

import static com.example.dispute.agentstream.application.AgentRunLedger.ATTEMPT_LINEAGE_SCHEMA_VERSION;

import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
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
import java.time.temporal.ChronoUnit;
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

    @Column(name = "lineage_schema_version", length = 64)
    private String lineageSchemaVersion;

    @Column(name = "command_id", length = 128)
    private String commandId;

    @Column(name = "command_request_hash", length = 64)
    private String commandRequestHash;

    @Column(name = "logical_input_hash", length = 64)
    private String logicalInputHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "command_json", columnDefinition = "jsonb")
    private String commandJson;

    @Column(name = "previous_attempt_id", length = 128)
    private String previousAttemptId;

    @Column(name = "reset_required", nullable = false)
    private boolean resetRequired;

    @Column(name = "public_sequence_offset", nullable = false)
    private int publicSequenceOffset;

    @Column(name = "termination_code", length = 128)
    private String terminationCode;

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
            String agentRunId,
            AttemptAllocation allocation,
            String previousAttemptId,
            boolean resetRequired,
            int publicSequenceOffset,
            Instant startedAt) {
        Objects.requireNonNull(allocation, "allocation");
        RoomGraphCommand command = allocation.command();
        requireEqual(command.logicalRunId(), agentRunId, "logicalRunId");
        requireEqual(command.requestHash(), allocation.binding().commandRequestHash(), "commandRequestHash");
        long attemptNo = positive(allocation.attemptNo(), "attemptNo");
        if (attemptNo == 1) {
            requireEqual(previousAttemptId, null, "previousAttemptId");
        } else {
            required(previousAttemptId, "previousAttemptId");
            if (command.attemptId().equals(previousAttemptId)) {
                throw new IllegalArgumentException("previousAttemptId cannot name the new attempt");
            }
        }
        requireSequenceOffset(resetRequired, publicSequenceOffset);

        AgentRunAttemptEntity attempt =
                new AgentRunAttemptEntity(required(command.attemptId(), "attemptId"));
        attempt.agentRunId = required(agentRunId, "agentRunId");
        attempt.attemptNo = attemptNo;
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
        attempt.lineageSchemaVersion = ATTEMPT_LINEAGE_SCHEMA_VERSION;
        attempt.commandId = required(command.commandId(), "commandId");
        attempt.commandRequestHash =
                sha256(allocation.binding().commandRequestHash(), "commandRequestHash");
        attempt.logicalInputHash =
                sha256(allocation.binding().logicalInputHash(), "logicalInputHash");
        attempt.commandJson =
                required(allocation.binding().canonicalCommandJson(), "canonicalCommandJson");
        attempt.previousAttemptId = previousAttemptId;
        attempt.resetRequired = resetRequired;
        attempt.publicSequenceOffset = publicSequenceOffset;
        attempt.lastSequenceNo = publicSequenceOffset;
        attempt.startedAt = at(startedAt, "startedAt");
        attempt.lastHeartbeatAt = attempt.startedAt;
        attempt.createdAt = attempt.startedAt;
        attempt.updatedAt = attempt.startedAt;
        attempt.createdBy = "temporal-agent-activity";
        return attempt;
    }

    /**
     * Starts the one outer attempt that owns a parallel Intake turn.
     *
     * <p>Frame-local generations own retry for this protocol, so V4 never carries a V3
     * predecessor/reset prelude. The first public Frame event owns global sequence zero.
     */
    public static AgentRunAttemptEntity startV4(
            String agentRunId, AttemptAllocation allocation, Instant startedAt) {
        Objects.requireNonNull(allocation, "allocation");
        if (allocation.attemptNo() != 1
                || !ExecuteAgentRunRequest.isParallelIntakeCommand(allocation.command())) {
            throw new IllegalArgumentException(
                    "agent-stream.v4 admission requires attempt one of an exact parallel Intake command");
        }
        AgentRunAttemptEntity attempt =
                start(agentRunId, allocation, null, false, 0, startedAt);
        attempt.lastSequenceNo = -1L;
        return attempt;
    }

    public void requireSameAllocation(AttemptAllocation allocation) {
        Objects.requireNonNull(allocation, "allocation");
        requireProofCarryingLineage();
        RoomGraphCommand command = allocation.command();
        requireEqual(agentRunId, command.logicalRunId(), "logicalRunId");
        requireEqual(getId(), command.attemptId(), "attemptId");
        requireEqual(attemptNo, allocation.attemptNo(), "attemptNo");
        requireEqual(commandId, command.commandId(), "commandId");
        requireEqual(
                commandRequestHash,
                allocation.binding().commandRequestHash(),
                "commandRequestHash");
        requireEqual(
                logicalInputHash,
                allocation.binding().logicalInputHash(),
                "logicalInputHash");
        requireEqual(graphKey, command.graphKey(), "graphKey");
        requireEqual(graphVersion, command.graphVersion(), "graphVersion");
        requireEqual(requestHash, command.requestHash(), "requestHash");
        requireEqual(
                modelProfileId,
                command.invocationContext().modelProfileId(),
                "modelProfileId");
    }

    public void requireAllocatedRequest(ExecuteAgentRunRequest request) {
        Objects.requireNonNull(request, "request");
        requireProofCarryingLineage();
        requireEqual(agentRunId, request.agentRunId(), "agentRunId");
        requireEqual(agentRunId, request.logicalRunId(), "logicalRunId");
        requireEqual(getId(), request.attemptId(), "attemptId");
        requireEqual(attemptNo, request.attemptNo(), "attemptNo");
        requireEqual(commandId, request.command().commandId(), "commandId");
        requireEqual(commandRequestHash, request.command().requestHash(), "commandRequestHash");
        requireEqual(logicalInputHash, request.logicalInputHash(), "logicalInputHash");
        requireEqual(previousAttemptId, request.previousAttemptId(), "previousAttemptId");
        requireEqual(resetRequired, request.resetRequired(), "resetRequired");
        requireEqual(
                publicSequenceOffset,
                request.publicSequenceOffset(),
                "publicSequenceOffset");
        requireEqual(graphKey, request.command().graphKey(), "graphKey");
        requireEqual(graphVersion, request.command().graphVersion(), "graphVersion");
        requireEqual(
                modelProfileId,
                request.command().invocationContext().modelProfileId(),
                "modelProfileId");
    }

    public void requireProofCarryingLineage() {
        requireEqual(
                lineageSchemaVersion,
                ATTEMPT_LINEAGE_SCHEMA_VERSION,
                "lineageSchemaVersion");
        required(commandId, "commandId");
        sha256(commandRequestHash, "commandRequestHash");
        sha256(logicalInputHash, "logicalInputHash");
        required(commandJson, "commandJson");
        if (attemptNo == 1) {
            requireEqual(previousAttemptId, null, "previousAttemptId");
        } else {
            required(previousAttemptId, "previousAttemptId");
        }
        requireSequenceOffset(resetRequired, publicSequenceOffset);
    }

    public void requireCanPrecede(long nextAttemptNo, String nextLogicalInputHash) {
        requireProofCarryingLineage();
        requireEqual(attemptNo + 1, nextAttemptNo, "attemptNo");
        requireEqual(logicalInputHash, nextLogicalInputHash, "logicalInputHash");
        requireEqual(
                terminationCode,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name(),
                "terminationCode");
        if (attemptStatus != AgentRunAttemptStatus.FAILED
                && attemptStatus != AgentRunAttemptStatus.ABORTED
                && attemptStatus != AgentRunAttemptStatus.CANCELLED) {
            throw new IllegalStateException(
                    "CREATE_NEXT_ATTEMPT requires a terminal predecessor");
        }
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
        lastSequenceNo = Math.max(lastSequenceNo, heartbeat.lastSequenceNo());
        publicOutputEmitted |= heartbeat.publicOutputEmitted();
        finalFrameObserved |= heartbeat.finalFrameObserved();
        OffsetDateTime recordedAt = at(heartbeat.recordedAt(), "recordedAt");
        if (recordedAt.isAfter(lastHeartbeatAt)) {
            lastHeartbeatAt = recordedAt;
        }
        updatedAt = lastHeartbeatAt;
    }

    public void recordResultReady(ExecuteAgentRunResult result, String serializedResult) {
        requireIdentity(result.agentRunId(), result.attemptId(), result.attemptNo());
        requireEqual(agentRunId, result.logicalRunId(), "logicalRunId");
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
        requireEqual(commandId, graphResult.commandId(), "commandId");
        requireEqual(graphKey, graphResult.graphKey(), "graphKey");
        requireEqual(graphVersion, graphResult.graphVersion(), "graphVersion");
        RoomGraphResult.ExecutionMetadata metadata = graphResult.executionMetadata();
        requireEqual(modelProfileId, metadata.modelProfileId(), "modelProfileId");
        requireEqual(outputSchemaVersion, metadata.schemaVersion(), "outputSchemaVersion");
        requireEqual(policyVersion, metadata.policyVersion(), "policyVersion");
        requireEqual(guardrailVersion, metadata.guardrailVersion(), "guardrailVersion");
        checkpointId = required(graphResult.checkpointId(), "checkpointId");
        promptVersion = required(metadata.promptVersion(), "promptVersion");
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
            AgentRunRecoveryAction recoveryAction,
            Instant failedAt) {
        if (status != AgentRunAttemptStatus.FAILED
                && status != AgentRunAttemptStatus.ABORTED
                && status != AgentRunAttemptStatus.CANCELLED) {
            throw new IllegalArgumentException("status must be a terminal attempt failure");
        }
        Objects.requireNonNull(recoveryAction, "recoveryAction");
        if (recoveryAction == AgentRunRecoveryAction.RETRY_SAME_COMMAND
                || recoveryAction == AgentRunRecoveryAction.RECONCILE_TERMINAL) {
            throw new IllegalArgumentException(
                    "Activity-local recovery cannot terminalize an AgentRun attempt");
        }
        boolean retryable = recoveryAction == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT;
        if (attemptStatus == status) {
            requireEqual(this.errorCode, errorCode, "errorCode");
            requireEqual(this.errorRetryable, retryable, "errorRetryable");
            requireEqual(
                    terminationCode,
                    recoveryAction.name(),
                    "terminationCode");
            return;
        }
        if (attemptStatus != AgentRunAttemptStatus.RUNNING) {
            throw new IllegalStateException("attempt cannot fail from " + attemptStatus);
        }
        attemptStatus = status;
        this.errorCode = required(errorCode, "errorCode");
        errorRetryable = retryable;
        terminationCode = recoveryAction.name();
        completedAt = at(failedAt, "failedAt");
        latencyMs = Math.max(0, Duration.between(startedAt, completedAt).toMillis());
        updatedAt = completedAt;
    }

    /**
     * Terminalizes a result-ready attempt after its formal finalizer rejects the immutable result.
     * The graph result and FINAL audit fields remain intact; only the public terminal cursor and
     * fixed failure authority advance.
     */
    public boolean recordFinalizationFailure(
            String agentRunId,
            long attemptNo,
            String commandId,
            String commandRequestHash,
            String resultHash,
            long finalSequenceNo,
            boolean publicOutputEmitted,
            AgentRunAttemptStatus terminalStatus,
            String safeErrorCode) {
        requireIdentity(agentRunId, getId(), attemptNo);
        requireEqual(this.commandId, commandId, "commandId");
        requireEqual(this.commandRequestHash, commandRequestHash, "commandRequestHash");
        requireEqual(this.resultHash, resultHash, "resultHash");
        requireEqual(this.publicOutputEmitted, publicOutputEmitted, "publicOutputEmitted");
        requireEqual(this.finalFrameObserved, true, "finalFrameObserved");
        AgentRunAttemptStatus expectedStatus = publicOutputEmitted
                ? AgentRunAttemptStatus.ABORTED
                : AgentRunAttemptStatus.FAILED;
        requireEqual(terminalStatus, expectedStatus, "terminalStatus");
        long terminalSequenceNo = Math.addExact(finalSequenceNo, 1L);

        if (attemptStatus == terminalStatus) {
            requireEqual(lastSequenceNo, terminalSequenceNo, "lastSequenceNo");
            requireEqual(errorCode, safeErrorCode, "errorCode");
            requireEqual(errorRetryable, false, "errorRetryable");
            requireEqual(
                    terminationCode,
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN.name(),
                    "terminationCode");
            return true;
        }
        if (attemptStatus != AgentRunAttemptStatus.RESULT_READY) {
            throw new IllegalStateException(
                    "finalization failure requires a result-ready attempt");
        }
        requireEqual(lastSequenceNo, finalSequenceNo, "lastSequenceNo");
        if (completedAt == null || resultJson == null) {
            throw new IllegalStateException(
                    "finalization failure requires the durable graph result audit");
        }
        attemptStatus = terminalStatus;
        errorCode = required(safeErrorCode, "safeErrorCode");
        errorRetryable = false;
        terminationCode = AgentRunRecoveryAction.FAIL_LOGICAL_RUN.name();
        lastSequenceNo = terminalSequenceNo;
        updatedAt = completedAt;
        return false;
    }

    /**
     * Advances only the durable public cursor for Java's global recovery error.
     *
     * <p>The preceding Activity failure remains the attempt authority: this transition must not
     * rewrite its status, error, recovery action, or completion timestamp.
     */
    public void advanceRecoveryTerminalErrorSequence(long terminalSequenceNo) {
        if (attemptStatus != AgentRunAttemptStatus.FAILED
                && attemptStatus != AgentRunAttemptStatus.ABORTED
                && attemptStatus != AgentRunAttemptStatus.CANCELLED) {
            throw new IllegalStateException(
                    "recovery terminal error requires a terminal failed attempt");
        }
        if (completedAt == null
                || errorCode == null
                || errorRetryable == null
                || terminationCode == null) {
            throw new IllegalStateException(
                    "recovery terminal error requires durable failure authority");
        }
        if (lastSequenceNo == Long.MAX_VALUE
                || terminalSequenceNo != lastSequenceNo + 1) {
            throw new IllegalStateException(
                    "recovery terminal error must be the exact next public sequence");
        }
        lastSequenceNo = terminalSequenceNo;
    }

    public void recordFailureResult(
            AgentRunAttemptStatus status,
            ExecuteAgentRunResult result,
            String serializedResult) {
        String encoded = required(serializedResult, "serializedResult");
        if (resultJson != null) {
            requireEqual(attemptStatus, status, "attemptStatus");
            requireDurableFailureResult(result);
            return;
        }
        requireDurableFailureResult(status, result);
        recordFailure(
                status,
                result.errorCode(),
                result.recoveryAction(),
                result.completedAt());
        resultJson = encoded;
    }

    /** Records one failed Activity result together with Java's exact next global terminal. */
    public void recordFailureResultWithTerminal(
            AgentRunAttemptStatus status,
            ExecuteAgentRunResult sourceResult,
            ExecuteAgentRunResult terminalResult,
            String serializedTerminalResult) {
        if (resultJson != null) {
            throw new IllegalStateException("durable Activity failure result already exists");
        }
        requireDurableFailureResult(status, sourceResult);
        long terminalSequence = Math.addExact(sourceResult.lastSequenceNo(), 1L);
        ExecuteAgentRunResult expected = new ExecuteAgentRunResult(
                sourceResult.schemaVersion(),
                sourceResult.agentRunId(),
                sourceResult.logicalRunId(),
                sourceResult.attemptId(),
                sourceResult.attemptNo(),
                sourceResult.outcome(),
                sourceResult.graphResult(),
                sourceResult.resultHash(),
                terminalSequence,
                sourceResult.publicOutputEmitted(),
                sourceResult.errorCode(),
                sourceResult.retryable(),
                sourceResult.recoveryAction(),
                sourceResult.completedAt());
        requireEqual(expected, terminalResult, "terminalResult");
        recordFailure(
                status,
                terminalResult.errorCode(),
                terminalResult.recoveryAction(),
                terminalResult.completedAt());
        lastSequenceNo = terminalSequence;
        resultJson = required(serializedTerminalResult, "serializedTerminalResult");
    }

    public void requireDurableFailureResult(ExecuteAgentRunResult result) {
        requireDurableFailureResult(attemptStatus, result);
        requireEqual(errorCode, result.errorCode(), "errorCode");
        requireEqual(terminationCode, result.recoveryAction().name(), "terminationCode");
        requireEqual(completedAt, at(result.completedAt(), "completedAt"), "completedAt");
    }

    private void requireDurableFailureResult(
            AgentRunAttemptStatus status, ExecuteAgentRunResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.completedAt().equals(
                result.completedAt().truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException(
                    "durable failure completedAt must use PostgreSQL microsecond precision");
        }
        if (status != AgentRunAttemptStatus.FAILED
                && status != AgentRunAttemptStatus.ABORTED) {
            throw new IllegalArgumentException(
                    "durable Activity failure status must be FAILED or ABORTED");
        }
        requireIdentity(result.agentRunId(), result.attemptId(), result.attemptNo());
        requireEqual(agentRunId, result.logicalRunId(), "logicalRunId");
        requireEqual(result.outcome(), ExecuteAgentRunResult.Outcome.FAILED, "outcome");
        requireEqual(lastSequenceNo, result.lastSequenceNo(), "lastSequenceNo");
        requireEqual(
                publicOutputEmitted,
                result.publicOutputEmitted(),
                "publicOutputEmitted");
        requireEqual(
                status,
                result.publicOutputEmitted()
                        ? AgentRunAttemptStatus.ABORTED
                        : AgentRunAttemptStatus.FAILED,
                "attemptStatus");
    }

    public void markCommitted(
            AgentExecutionManifest manifest,
            long finalStreamSequenceNo,
            String expectedStreamProtocol) {
        requireEqual(getId(), manifest.agentRun().attemptId(), "attemptId");
        if (attemptStatus != AgentRunAttemptStatus.RESULT_READY
                && attemptStatus != AgentRunAttemptStatus.COMPLETED) {
            throw new IllegalStateException("attempt result is not ready for commit");
        }
        requireEqual(agentRunId, manifest.agentRun().logicalRunId(), "logicalRunId");
        requireEqual(graphKey, manifest.graph().graphKey(), "graphKey");
        requireEqual(graphVersion, manifest.graph().graphVersion(), "graphVersion");
        requireEqual(
                checkpointSchemaVersion,
                manifest.graph().checkpointSchemaVersion(),
                "checkpointSchemaVersion");
        requireEqual(checkpointId, manifest.graph().checkpointId(), "checkpointId");
        requireEqual(promptVersion, manifest.model().promptVersion(), "promptVersion");
        requireEqual(modelProfileId, manifest.model().modelProfileId(), "modelProfileId");
        requireEqual(requestHash, manifest.model().requestHash(), "requestHash");
        requireEqual(resultHash, manifest.model().responseHash(), "responseHash");
        requireEqual(resultHash, manifest.output().sha256(), "outputHash");
        requireEqual(policyVersion, manifest.policyVersion(), "policyVersion");
        requireEqual(guardrailVersion, manifest.guardrailVersion(), "guardrailVersion");
        requireEqual(inputTokens, manifest.usage().inputTokens(), "inputTokens");
        requireEqual(outputTokens, manifest.usage().outputTokens(), "outputTokens");
        requireEqual(totalTokens, manifest.usage().totalTokens(), "totalTokens");
        requireEqual(latencyMs, manifest.usage().latencyMs(), "latencyMs");
        requireEqual(lastSequenceNo, finalStreamSequenceNo, "finalStreamSequenceNo");
        if (!finalFrameObserved) {
            throw new IllegalStateException("formal manifest requires an observed final frame");
        }
        requireEqual(
                "room-graph-command.v1",
                manifest.contractVersions().get("graph_command"),
                "graphCommandSchemaVersion");
        requireEqual(
                "room-graph-result.v1",
                manifest.contractVersions().get("graph_result"),
                "graphResultSchemaVersion");
        requireEqual(
                outputSchemaVersion,
                manifest.contractVersions().get("output_schema"),
                "outputSchemaVersion");
        requireEqual(
                requiredTemporalStreamProtocol(expectedStreamProtocol),
                manifest.contractVersions().get("stream"),
                "streamProtocol");
        if (manifest.finalizedAt().isBefore(completedAt.toInstant())) {
            throw new IllegalStateException("manifest finalizedAt precedes attempt completion");
        }
        if (attemptStatus == AgentRunAttemptStatus.COMPLETED) {
            requireEqual(provider, manifest.model().provider(), "provider");
            requireEqual(modelVersion, manifest.model().model(), "modelVersion");
            return;
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

    private static String requiredTemporalStreamProtocol(String value) {
        if (!AgentRunProtocol.V2.wireValue().equals(value)
                && !AgentRunProtocol.V3.wireValue().equals(value)
                && !AgentRunProtocol.V4.wireValue().equals(value)) {
            throw new IllegalArgumentException(
                    "expectedStreamProtocol must identify a versioned Temporal stream");
        }
        return value;
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

    public String getLineageSchemaVersion() {
        return lineageSchemaVersion;
    }

    public String getCommandId() {
        return commandId;
    }

    public String getCommandRequestHash() {
        return commandRequestHash;
    }

    public String getLogicalInputHash() {
        return logicalInputHash;
    }

    public String getCommandJson() {
        return commandJson;
    }

    public String getPreviousAttemptId() {
        return previousAttemptId;
    }

    public boolean isResetRequired() {
        return resetRequired;
    }

    public int getPublicSequenceOffset() {
        return publicSequenceOffset;
    }

    public String getTerminationCode() {
        return terminationCode;
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

    private static void requireSequenceOffset(boolean resetRequired, int publicSequenceOffset) {
        int expected = resetRequired ? 1 : 0;
        if (publicSequenceOffset != expected) {
            throw new IllegalArgumentException(
                    "publicSequenceOffset must be derived from resetRequired");
        }
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
