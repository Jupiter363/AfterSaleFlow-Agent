package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.runtime.graph.HttpProductionGraphReconciliationClient;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeSigner;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Durable Review result evidence. The Activity payload supplies lookup keys only; the admitted
 * command, target Graph envelope, and AgentRun lifecycle are read again before Java trusts them.
 */
public final class ReconciledTargetReviewFinalizationEvidenceSource
    implements TargetReviewReconciledFinalizationEvidenceSource {
  private static final String RESULT_SCHEMA = "room-graph-result.schema.json";
  private static final AgentPlatformContractCodec CONTRACT_CODEC = new AgentPlatformContractCodec();
  private static final String SQL_RUN = """
      select r.tenant_surrogate, r.case_id, r.room_id, r.room_type, r.room_epoch, r.process_revision,
             r.fencing_token, r.protocol, r.executor_kind as run_executor_kind, r.run_status,
             r.finalization_status, r.result_ready_attempt_id, r.committed_attempt_id,
             r.final_result_hash, a.id, a.attempt_no, a.attempt_status,
             a.executor_kind as attempt_executor_kind, a.provider, a.model_version, a.graph_key,
             a.graph_version, a.checkpoint_schema_version, a.checkpoint_id, a.request_hash,
             a.result_hash, a.latency_ms, a.completed_at, a.final_frame_observed, a.last_sequence_no
        from agent_run r join agent_run_attempt a on a.agent_run_id = r.id
       where r.id = ? and a.id = ?
      """;

  private final JdbcTemplate jdbc;
  private final ProductionActivationLedger activationLedger;
  private final ProductionGraphEnvelopeCodec codec;
  private final ProductionGraphEnvelopeSigner signer;
  private final HttpProductionGraphReconciliationClient reconciliation;
  private final GraphRegistryBindingPolicy registryBindings;

  public ReconciledTargetReviewFinalizationEvidenceSource(
      DataSource dataSource, ProductionActivationLedger activationLedger,
      ProductionGraphEnvelopeCodec codec, ProductionGraphEnvelopeSigner signer,
      HttpProductionGraphReconciliationClient reconciliation,
      GraphRegistryBindingPolicy registryBindings) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.activationLedger = Objects.requireNonNull(activationLedger, "activationLedger");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
    this.registryBindings = Objects.requireNonNull(registryBindings, "registryBindings");
  }

  @Override
  public Evidence resolve(TargetReviewCommandMaterialStore.Snapshot material,
      ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
    Objects.requireNonNull(material, "material");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(result, "result");
    var command = request.command();
    require(result.outcome() == ExecuteAgentRunResult.Outcome.COMPLETED && result.graphResult() != null
        && result.graphResult().artifactOperations().size() == 1
        && result.graphResult().artifactOperations().getFirst().operation() == ArtifactOperationType.PROPOSE_PATCH,
        "completed proposal-only result");
    DurableRun durable = requireOne(jdbc.query(SQL_RUN, ReconciledTargetReviewFinalizationEvidenceSource::run,
        request.agentRunId(), request.attemptId()), "target Review AgentRun/attempt");
    require(durable.matches(material, request, result), "durable AgentRun/attempt bindings");
    var admission = activationLedger.queryCommandAdmission(material.admission().activationId(), command.commandId())
        .orElseThrow(() -> new IllegalStateException("target Review command admission disappeared"));
    require(admission.activationId().equals(material.admission().activationId()), "activation admission");
    require(admission.activationManifestHash().equals(material.admission().manifestHash()), "manifest admission");
    require(admission.isolatedDomainDbBindingHash().equals(material.admission().isolatedDomainDbBindingHash()),
        "isolated Domain DB admission");
    require(admission.commandHash().equals(material.admission().commandHash()), "command hash admission");
    require(admission.commandEnvelopeHash().equals(material.admission().commandEnvelopeHash()),
        "command envelope admission");
    require(admission.roomFencingToken() == material.admission().roomFencingToken(), "fence admission");

    var expectedBinding = GraphRegistryBindingPolicy.requireExpected(
        registryBindings, GraphStreamVisibilityPolicy.Binding.from(command));
    var sealed = codec.sealCommand(material.admission().activationId(), material.admission().roomFencingToken(),
        command, expectedBinding, signer);
    var envelope = reconciliation.reconcileAvailable(sealed, new AgentRunCancellationToken()).envelope();
    requireCanonicalResultEqual(envelope.result(), result.graphResult(), "reconciled Graph result");
    require(envelope.resultHash().equals(result.resultHash()), "reconciled result hash");
    require(envelope.activationId().equals(material.admission().activationId()), "reconciled activation");
    require(envelope.roomFencingToken() == material.admission().roomFencingToken(), "reconciled fence");
    require(envelope.commandHash().equals(material.admission().commandHash()), "reconciled command hash");
    require(envelope.commandEnvelopeHash().equals(material.admission().commandEnvelopeHash()),
        "reconciled command envelope hash");
    require(durable.executionIdentityMatches(envelope.executionProvider(), envelope.executionModel()),
        "reconciled execution identity");
    return new Evidence(durable.roomId(), envelope.proposalHash(), envelope.resultEnvelopeHash(),
        envelope.executionProvider(), envelope.executionModel());
  }

  private static DurableRun run(ResultSet row, int ignored) throws SQLException {
    OffsetDateTime completed = row.getObject("completed_at", OffsetDateTime.class);
    Long latency = row.getObject("latency_ms", Long.class);
    return new DurableRun(row.getString("tenant_surrogate"), row.getString("case_id"),
        row.getString("room_id"), row.getString("room_type"),
        row.getLong("room_epoch"), row.getLong("process_revision"), row.getLong("fencing_token"),
        row.getString("protocol"), row.getString("run_executor_kind"), row.getString("run_status"),
        row.getString("finalization_status"), row.getString("result_ready_attempt_id"),
        row.getString("committed_attempt_id"), row.getString("final_result_hash"), row.getString("id"),
        row.getLong("attempt_no"), row.getString("attempt_status"), row.getString("attempt_executor_kind"),
        row.getString("provider"), row.getString("model_version"), row.getString("graph_key"),
        row.getString("graph_version"), row.getString("checkpoint_schema_version"), row.getString("checkpoint_id"),
        row.getString("request_hash"), row.getString("result_hash"), latency == null ? -1L : latency,
        completed == null ? null : completed.toInstant(), row.getBoolean("final_frame_observed"),
        row.getLong("last_sequence_no"));
  }

  private static <T> T requireOne(List<T> rows, String label) {
    if (rows.size() != 1) throw new IllegalStateException(label + " is absent or ambiguous");
    return rows.getFirst();
  }

  private static void requireCanonicalResultEqual(RoomGraphResult actual, RoomGraphResult expected, String label) {
    require(ContractJson.canonicalString(CONTRACT_CODEC.encode(RESULT_SCHEMA, actual))
        .equals(ContractJson.canonicalString(CONTRACT_CODEC.encode(RESULT_SCHEMA, expected))), label);
  }

  private static void require(boolean condition, String label) {
    if (!condition) throw new IllegalStateException("target Review " + label + " is inconsistent");
  }

  private record DurableRun(String tenant, String caseId, String roomId, String roomType, long roomEpoch,
      long processRevision, long fencingToken, String protocol, String runExecutor, String runStatus,
      String finalizationStatus, String resultReadyAttemptId, String committedAttemptId,
      String finalResultHash, String attemptId, long attemptNo, String attemptStatus,
      String attemptExecutor, String provider, String modelVersion, String graphKey, String graphVersion,
      String checkpointSchemaVersion, String checkpointId, String requestHash, String resultHash,
      long latencyMs, Instant completedAt, boolean finalFrameObserved, long lastSequenceNo) {
    boolean matches(TargetReviewCommandMaterialStore.Snapshot material, ExecuteAgentRunRequest request,
        ExecuteAgentRunResult result) {
      var command = request.command();
      boolean resultReady = "RESULT_READY".equals(runStatus) && "UNCOMMITTED".equals(finalizationStatus)
          && "RESULT_READY".equals(attemptStatus) && committedAttemptId == null;
      boolean completedReplay = "COMPLETED".equals(runStatus) && "COMMITTED".equals(finalizationStatus)
          && "COMPLETED".equals(attemptStatus) && attemptId.equals(committedAttemptId);
      boolean executionLifecycle = resultReady ? provider == null && modelVersion == null
          : completedReplay && provider != null && !provider.isBlank()
              && modelVersion != null && !modelVersion.isBlank();
      return (resultReady || completedReplay) && executionLifecycle
          && "agent-stream.v3".equals(protocol) && "TEMPORAL_ACTIVITY".equals(runExecutor)
          && "TEMPORAL_ACTIVITY".equals(attemptExecutor) && "REVIEW".equals(roomType)
          && roomId != null && !roomId.isBlank()
          && tenant.equals(command.tenantSurrogate()) && caseId.equals(command.caseId())
          && roomEpoch == command.roomEpoch() && processRevision == command.processRevision()
          && fencingToken == material.admission().roomFencingToken()
          && attemptId.equals(request.attemptId()) && attemptNo == request.attemptNo()
          && attemptId.equals(resultReadyAttemptId) && resultHash.equals(result.resultHash())
          && resultHash.equals(finalResultHash) && graphKey.equals(command.graphKey())
          && graphVersion.equals(command.graphVersion())
          && checkpointSchemaVersion.equals(command.checkpointSchemaVersion())
          && checkpointId.equals(result.graphResult().checkpointId())
          && requestHash.equals(command.requestHash()) && finalFrameObserved && completedAt != null
          && latencyMs >= 0 && lastSequenceNo == result.lastSequenceNo();
    }

    boolean executionIdentityMatches(String executionProvider, String executionModel) {
      if ("RESULT_READY".equals(runStatus)) return provider == null && modelVersion == null;
      return provider != null && !provider.isBlank() && modelVersion != null && !modelVersion.isBlank()
          && provider.equals(executionProvider) && modelVersion.equals(executionModel);
    }
  }
}
