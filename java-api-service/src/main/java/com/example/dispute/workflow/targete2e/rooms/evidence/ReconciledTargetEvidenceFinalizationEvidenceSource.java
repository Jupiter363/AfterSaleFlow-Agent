package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Production target Evidence evidence source.
 *
 * <p>It does not accept the Activity's input result as proof: the durable AgentRun/attempt and
 * immutable output snapshot are re-read from PostgreSQL, while the Graph result envelope and
 * proposal source are re-reconciled through the existing authenticated Graph client.
 */
public final class ReconciledTargetEvidenceFinalizationEvidenceSource
    implements TargetEvidenceFinalizationEvidenceSource {
  private final JdbcTemplate jdbc;
  private final TargetE2EActivationLedger activationLedger;
  private final TargetE2EGraphEnvelopeCodec codec;
  private final TargetE2EGraphEnvelopeSigner signer;
  private final HttpTargetE2EGraphReconciliationClient reconciliation;
  private final GraphRegistryBindingPolicy registryBindings;
  private final TargetE2eFinalizationRuntimeContextProvider runtime;

  public ReconciledTargetEvidenceFinalizationEvidenceSource(
      DataSource dataSource,
      TargetE2EActivationLedger activationLedger,
      TargetE2EGraphEnvelopeCodec codec,
      TargetE2EGraphEnvelopeSigner signer,
      HttpTargetE2EGraphReconciliationClient reconciliation,
      GraphRegistryBindingPolicy registryBindings,
      TargetE2eFinalizationRuntimeContextProvider runtime) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.activationLedger = Objects.requireNonNull(activationLedger, "activationLedger");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
    this.registryBindings = Objects.requireNonNull(registryBindings, "registryBindings");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public Evidence resolve(
      TargetEvidenceCommandMaterialStore.MaterialSnapshot material,
      ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result) {
    Objects.requireNonNull(material, "material");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(result, "result");
    var graph = request.command();
    DurableRun durable = requireOne(jdbc.query(SQL_RUN, ReconciledTargetEvidenceFinalizationEvidenceSource::run,
        request.agentRunId(), request.attemptId()), "target Evidence AgentRun/attempt");
    require(durable.matches(material, request, result), "durable AgentRun/attempt bindings");
    ArtifactPointer output = requireOne(jdbc.query(SQL_OUTPUT,
        ReconciledTargetEvidenceFinalizationEvidenceSource::output,
        graph.tenantSurrogate(), graph.caseId(), result.resultHash()), "target Evidence immutable FINAL output");
    var admission = activationLedger.queryCommandAdmission(material.material().activationId(), graph.commandId())
        .orElseThrow(() -> new IllegalStateException("target Evidence command admission disappeared"));
    require(admission.activationId().equals(material.material().activationId()), "activation id admission");
    require(admission.activationManifestHash().equals(material.material().activationManifestHash()), "activation manifest admission");
    require(admission.isolatedDomainDbBindingHash().equals(material.admission().isolatedDomainDbBindingHash()), "isolated DB admission");
    require(admission.tenantSurrogate().equals(graph.tenantSurrogate()) && admission.caseId().equals(graph.caseId()),
        "case scope admission");
    require(admission.roomEpoch() == graph.roomEpoch(), "epoch admission");
    require(admission.commandHash().equals(material.material().commandHash()), "command hash admission");
    require(admission.commandEnvelopeHash().equals(material.material().commandEnvelopeHash()), "envelope hash admission");
    require(admission.roomFencingToken() == material.material().roomFencingToken(), "fence admission");

    var expectedBinding = GraphRegistryBindingPolicy.requireExpected(
        registryBindings, GraphStreamVisibilityPolicy.Binding.from(graph));
    var sealed = codec.sealCommand(material.material().activationId(), material.material().roomFencingToken(),
        graph, expectedBinding, signer);
    var reconciled = reconciliation.reconcileAvailable(sealed, new AgentRunCancellationToken());
    var envelope = reconciled.envelope();
    require(envelope.result().equals(result.graphResult()), "reconciled Graph result");
    require(envelope.resultHash().equals(result.resultHash()), "reconciled result hash");
    require(envelope.activationId().equals(material.material().activationId()), "reconciled activation");
    require(envelope.roomFencingToken() == material.material().roomFencingToken(), "reconciled fence");
    require(envelope.commandHash().equals(material.material().commandHash()), "reconciled command hash");
    require(envelope.commandEnvelopeHash().equals(material.material().commandEnvelopeHash()), "reconciled envelope hash");
    var runtimeContext = runtime.current();
    return new Evidence(
        durable.roomId, durable.fencingToken, material.admission().isolatedDomainDbBindingHash(),
        envelope.proposalHash(), envelope.resultEnvelopeHash(), durable.logicalIdempotencyKey,
        durable.provider, durable.modelVersion, runtimeContext.workflowId(), runtimeContext.workflowRunId(),
        runtimeContext.workflowBuildId(), output, List.of(), durable.latencyMs, durable.completedAt);
  }

  private static final String SQL_RUN = """
      select r.tenant_surrogate, r.case_id, r.room_id, r.room_type, r.room_epoch, r.process_revision,
             r.fencing_token, r.logical_idempotency_key, r.protocol, r.executor_kind as run_executor_kind, r.run_status,
             r.finalization_status, r.result_ready_attempt_id, r.committed_attempt_id, r.final_result_hash,
             a.id, a.attempt_no, a.attempt_status, a.executor_kind as attempt_executor_kind, a.provider, a.model_version,
             a.graph_key, a.graph_version, a.checkpoint_schema_version, a.checkpoint_id,
             a.request_hash, a.result_hash, a.latency_ms, a.completed_at, a.final_frame_observed, a.last_sequence_no
        from agent_run r join agent_run_attempt a on a.agent_run_id = r.id
       where r.id = ? and a.id = ?
      """;
  private static final String SQL_OUTPUT = """
      select id, schema_version, object_uri, content_sha256
        from immutable_payload_snapshot
       where tenant_surrogate = ? and case_id = ? and content_sha256 = ? and room_type = 'EVIDENCE'
      """;

  private static DurableRun run(ResultSet row, int ignored) throws SQLException {
    OffsetDateTime completed = row.getObject("completed_at", OffsetDateTime.class);
    Long latency = row.getObject("latency_ms", Long.class);
    return new DurableRun(row.getString("tenant_surrogate"), row.getString("case_id"), row.getString("room_id"),
        row.getString("room_type"), row.getLong("room_epoch"), row.getLong("process_revision"),
        row.getLong("fencing_token"), row.getString("logical_idempotency_key"), row.getString("protocol"),
        row.getString("run_executor_kind"), row.getString("run_status"), row.getString("finalization_status"),
        row.getString("result_ready_attempt_id"), row.getString("committed_attempt_id"), row.getString("final_result_hash"),
        row.getString("id"), row.getLong("attempt_no"), row.getString("attempt_status"),
        row.getString("attempt_executor_kind"), row.getString("provider"), row.getString("model_version"), row.getString("graph_key"),
        row.getString("graph_version"), row.getString("checkpoint_schema_version"), row.getString("checkpoint_id"),
        row.getString("request_hash"), row.getString("result_hash"), latency == null ? -1L : latency,
        completed == null ? null : completed.toInstant(), row.getBoolean("final_frame_observed"), row.getLong("last_sequence_no"));
  }

  private static ArtifactPointer output(ResultSet row, int ignored) throws SQLException {
    return new ArtifactPointer(row.getString("id"), row.getString("schema_version"),
        row.getString("object_uri"), row.getString("content_sha256"));
  }

  private static <T> T requireOne(List<T> values, String label) {
    if (values.size() != 1) throw new IllegalStateException(label + " is absent or ambiguous");
    return values.getFirst();
  }

  private static void require(boolean condition, String label) {
    if (!condition) throw new IllegalStateException("target Evidence " + label + " is inconsistent");
  }

  private record DurableRun(
      String tenant, String caseId, String roomId, String roomType, long roomEpoch, long processRevision,
      long fencingToken, String logicalIdempotencyKey, String protocol, String runExecutor, String runStatus,
      String finalizationStatus, String resultReadyAttemptId, String committedAttemptId, String finalResultHash,
      String attemptId, long attemptNo, String attemptStatus, String attemptExecutor, String provider,
      String modelVersion, String graphKey, String graphVersion, String checkpointSchemaVersion, String checkpointId,
      String requestHash, String resultHash, long latencyMs, Instant completedAt, boolean finalFrameObserved,
      long lastSequenceNo) {
    boolean matches(TargetEvidenceCommandMaterialStore.MaterialSnapshot material,
        ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
      var command = request.command();
      boolean terminal = ("RESULT_READY".equals(runStatus) && "UNCOMMITTED".equals(finalizationStatus)
          && "RESULT_READY".equals(attemptStatus) && committedAttemptId == null)
          || ("COMPLETED".equals(runStatus) && "COMMITTED".equals(finalizationStatus)
          && "COMPLETED".equals(attemptStatus) && attemptId.equals(committedAttemptId));
      return terminal && "agent-stream.v2".equals(protocol) && "TEMPORAL_ACTIVITY".equals(runExecutor)
          && "TEMPORAL_ACTIVITY".equals(attemptExecutor) && "EVIDENCE".equals(roomType)
          && tenant.equals(command.tenantSurrogate()) && caseId.equals(command.caseId()) && roomEpoch == command.roomEpoch()
          && processRevision == command.processRevision() && fencingToken == material.material().roomFencingToken()
          && attemptId.equals(request.attemptId()) && attemptNo == request.attemptNo()
          && attemptId.equals(resultReadyAttemptId) && resultHash.equals(result.resultHash())
          && resultHash.equals(finalResultHash) && graphKey.equals(command.graphKey())
          && graphVersion.equals(command.graphVersion()) && checkpointSchemaVersion.equals(command.checkpointSchemaVersion())
          && checkpointId.equals(result.graphResult().checkpointId()) && requestHash.equals(command.requestHash())
          && finalFrameObserved && completedAt != null && latencyMs >= 0 && provider != null && !provider.isBlank()
          && modelVersion != null && !modelVersion.isBlank() && lastSequenceNo == result.lastSequenceNo();
    }
  }
}
