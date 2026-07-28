package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.graph.HttpTargetE2EGraphReconciliationClient;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeSigner;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphProposalPayloadSource;
import com.example.dispute.workflow.targete2e.graph.TargetE2ERoomProposalSource;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Production, durable Hearing finalization evidence resolver.
 *
 * <p>It treats the Activity's request/result as lookup keys only. AgentRun, attempt, immutable
 * FINAL output, admission, target Graph result envelope and proposal source are all re-read from
 * their authoritative stores before any Java Hearing fact can be committed.
 */
public final class ReconciledTargetHearingFinalizationEvidenceResolver
    implements TargetHearingFinalizationEvidenceResolver {

  private static final String SQL_RUN = """
      select r.tenant_surrogate, r.case_id, r.room_id, r.room_type, r.room_epoch, r.process_revision,
             r.fencing_token, r.logical_idempotency_key, r.protocol, r.executor_kind as run_executor_kind,
             r.run_status, r.finalization_status, r.result_ready_attempt_id, r.committed_attempt_id,
             r.final_result_hash, a.id, a.attempt_no, a.attempt_status,
             a.executor_kind as attempt_executor_kind, a.provider, a.model_version, a.graph_key,
             a.graph_version, a.checkpoint_schema_version, a.checkpoint_id, a.request_hash,
             a.result_hash, a.latency_ms, a.completed_at, a.final_frame_observed, a.last_sequence_no
        from agent_run r
        join agent_run_attempt a on a.agent_run_id = r.id
       where r.id = ? and a.id = ?
      """;
  private static final String SQL_OUTPUT = """
      select id, schema_version, object_uri, content_sha256
        from immutable_payload_snapshot
       where tenant_surrogate = ? and case_id = ? and room_type = 'HEARING'
         and snapshot_type = 'AGENT_OUTPUT' and source_type = 'AGENT_RUN' and source_id = ?
         and content_sha256 = ?
      """;

  private final JdbcTemplate jdbc;
  private final TargetE2EActivationLedger activationLedger;
  private final TargetE2EGraphEnvelopeCodec codec;
  private final TargetE2EGraphEnvelopeSigner signer;
  private final HttpTargetE2EGraphReconciliationClient reconciliation;
  private final TargetE2EGraphProposalPayloadSource proposalSource;
  private final ObjectMapper objectMapper;
  private final GraphRegistryBindingPolicy registryBindings;
  private final TargetE2eFinalizationRuntimeContextProvider runtime;

  public ReconciledTargetHearingFinalizationEvidenceResolver(
      DataSource dataSource,
      TargetE2EActivationLedger activationLedger,
      TargetE2EGraphEnvelopeCodec codec,
      TargetE2EGraphEnvelopeSigner signer,
      HttpTargetE2EGraphReconciliationClient reconciliation,
      TargetE2EGraphProposalPayloadSource proposalSource,
      GraphRegistryBindingPolicy registryBindings,
      TargetE2eFinalizationRuntimeContextProvider runtime,
      ObjectMapper objectMapper) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.activationLedger = Objects.requireNonNull(activationLedger, "activationLedger");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
    this.proposalSource = Objects.requireNonNull(proposalSource, "proposalSource");
    this.registryBindings = Objects.requireNonNull(registryBindings, "registryBindings");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
  }

  @Override
  public TargetHearingFinalizationEvidence resolve(
      ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result,
      TargetHearingCommandMaterialStore.Snapshot material) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(material, "material");
    var command = request.command();
    require(result.outcome() == ExecuteAgentRunResult.Outcome.COMPLETED
        && result.graphResult() != null
        && result.graphResult().artifactOperations().size() == 1
        && result.graphResult().artifactOperations().getFirst().operation()
            == ArtifactOperationType.PROPOSE_CREATE, "completed proposal-only result");
    DurableRun durable = requireOne(jdbc.query(SQL_RUN,
        ReconciledTargetHearingFinalizationEvidenceResolver::run,
        request.agentRunId(), request.attemptId()), "target Hearing AgentRun/attempt");
    require(durable.matches(material, request, result), "durable AgentRun/attempt bindings");
    ArtifactPointer output = requireOne(jdbc.query(SQL_OUTPUT,
        ReconciledTargetHearingFinalizationEvidenceResolver::output,
        command.tenantSurrogate(), command.caseId(), request.agentRunId(), result.resultHash()),
        "target Hearing immutable FINAL output");

    var admission = activationLedger.queryCommandAdmission(
        material.admission().activationId(), command.commandId())
        .orElseThrow(() -> new IllegalStateException("target Hearing command admission disappeared"));
    require(admission.activationManifestHash().equals(material.admission().manifestHash()),
        "activation manifest admission");
    require(admission.isolatedDomainDbBindingHash().equals(
        material.admission().isolatedDomainDbBindingHash()), "isolated Domain binding admission");
    require(admission.commandHash().equals(material.admission().commandHash()), "command hash admission");
    require(admission.commandEnvelopeHash().equals(material.admission().commandEnvelopeHash()),
        "command envelope admission");
    require(admission.roomFencingToken() == material.admission().roomFencingToken(), "fence admission");

    var expectedBinding = GraphRegistryBindingPolicy.requireExpected(
        registryBindings, GraphStreamVisibilityPolicy.Binding.from(command));
    var sealed = codec.sealCommand(material.admission().activationId(),
        material.admission().roomFencingToken(), command, expectedBinding, signer);
    var reconciled = reconciliation.reconcileAvailable(sealed, new AgentRunCancellationToken());
    var envelope = reconciled.envelope();
    require(envelope.result().equals(result.graphResult()), "reconciled Graph result");
    require(envelope.resultHash().equals(result.resultHash()), "reconciled result hash");
    require(envelope.activationId().equals(material.admission().activationId()), "reconciled activation");
    require(envelope.roomFencingToken() == material.admission().roomFencingToken(), "reconciled fence");
    require(envelope.commandHash().equals(material.admission().commandHash()), "reconciled command hash");
    require(envelope.commandEnvelopeHash().equals(material.admission().commandEnvelopeHash()),
        "reconciled command envelope hash");
    require(output.sha256().equals(envelope.resultHash()), "immutable FINAL output hash");
    require(output.uri().equals(reconciled.resultRef()), "immutable FINAL output reference");
    TargetE2ERoomProposalSource.Proposal proposalDescriptor;
    try {
      byte[] source = proposalSource.loadSchemaValidatedProposalSource(
          sealed, reconciled.resultRef(), envelope.proposalHash(), new AgentRunCancellationToken());
      proposalDescriptor = objectMapper.readValue(source, TargetE2ERoomProposalSource.class).proposal();
    } catch (Exception failure) {
      throw new IllegalStateException("target Hearing reconciled proposal source is unreadable", failure);
    }
    ArtifactPointer proposal = result.graphResult().artifactOperations().getFirst().artifact();
    require(proposal.artifactId().equals(proposalDescriptor.proposalId())
        && proposal.schemaVersion().equals(proposalDescriptor.payloadSchemaVersion())
        && proposal.uri().equals(proposalDescriptor.payloadRef())
        && proposal.sha256().equals(proposalDescriptor.payloadHash())
        && proposalDescriptor.commandId().equals(command.commandId())
        && proposalDescriptor.logicalRunId().equals(command.logicalRunId())
        && proposalDescriptor.attemptId().equals(command.attemptId())
        && !proposalDescriptor.formalAuthority(), "reconciled Hearing proposal descriptor");

    var runtimeContext = Objects.requireNonNull(runtime.current(), "target Hearing runtime context");
    AgentRunV2ManifestFactory.FinalizationFacts facts = new AgentRunV2ManifestFactory.FinalizationFacts(
        durable.fencingToken, durable.logicalIdempotencyKey, runtimeContext.workflowId(),
        runtimeContext.workflowRunId(), runtimeContext.workflowBuildId(), durable.provider,
        durable.modelVersion, "urn:target-e2e:agent-manifest:" + material.admission().activationId()
            + ':' + request.agentRunId() + ':' + request.attemptId() + ':' + result.resultHash(),
        output, List.of(), List.of(), durable.latencyMs, durable.completedAt);
    return new TargetHearingFinalizationEvidence(material.admission().manifestHash(),
        material.admission().activationId(), durable.roomId, runtimeContext.workflowId(),
        runtimeContext.workflowRunId(), runtimeContext.workflowBuildId(),
        material.admission().isolatedDomainDbBindingHash(), material.admission().commandHash(),
        material.admission().commandEnvelopeHash(), material.admission().roomFencingToken(),
        material.materialSha256(), proposal, proposalDescriptor,
        envelope.proposalHash(), envelope.resultEnvelopeHash(), facts, durable.completedAt);
  }

  private static DurableRun run(ResultSet row, int ignored) throws SQLException {
    OffsetDateTime completed = row.getObject("completed_at", OffsetDateTime.class);
    return new DurableRun(row.getString("tenant_surrogate"), row.getString("case_id"),
        row.getString("room_id"), row.getString("room_type"), row.getLong("room_epoch"),
        row.getLong("process_revision"), row.getLong("fencing_token"),
        row.getString("logical_idempotency_key"), row.getString("protocol"),
        row.getString("run_executor_kind"), row.getString("run_status"),
        row.getString("finalization_status"), row.getString("result_ready_attempt_id"),
        row.getString("committed_attempt_id"), row.getString("final_result_hash"), row.getString("id"),
        row.getLong("attempt_no"), row.getString("attempt_status"),
        row.getString("attempt_executor_kind"), row.getString("provider"), row.getString("model_version"),
        row.getString("graph_key"), row.getString("graph_version"),
        row.getString("checkpoint_schema_version"), row.getString("checkpoint_id"),
        row.getString("request_hash"), row.getString("result_hash"), row.getLong("latency_ms"),
        completed == null ? null : completed.toInstant(), row.getBoolean("final_frame_observed"),
        row.getLong("last_sequence_no"));
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
    if (!condition) throw new IllegalStateException("target Hearing " + label + " is inconsistent");
  }

  private record DurableRun(
      String tenant, String caseId, String roomId, String roomType, long roomEpoch, long processRevision,
      long fencingToken, String logicalIdempotencyKey, String protocol, String runExecutor, String runStatus,
      String finalizationStatus, String resultReadyAttemptId, String committedAttemptId, String finalResultHash,
      String attemptId, long attemptNo, String attemptStatus, String attemptExecutor, String provider,
      String modelVersion, String graphKey, String graphVersion, String checkpointSchemaVersion,
      String checkpointId, String requestHash, String resultHash, long latencyMs, Instant completedAt,
      boolean finalFrameObserved, long lastSequenceNo) {
    boolean matches(TargetHearingCommandMaterialStore.Snapshot material,
        ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
      var command = request.command();
      boolean terminal = ("RESULT_READY".equals(runStatus) && "UNCOMMITTED".equals(finalizationStatus)
          && "RESULT_READY".equals(attemptStatus) && committedAttemptId == null)
          || ("COMPLETED".equals(runStatus) && "COMMITTED".equals(finalizationStatus)
          && "COMPLETED".equals(attemptStatus) && attemptId.equals(committedAttemptId));
      return terminal && "agent-stream.v2".equals(protocol) && "TEMPORAL_ACTIVITY".equals(runExecutor)
          && "TEMPORAL_ACTIVITY".equals(attemptExecutor) && "HEARING".equals(roomType)
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
          && latencyMs >= 0 && provider != null && !provider.isBlank()
          && modelVersion != null && !modelVersion.isBlank() && lastSequenceNo == result.lastSequenceNo();
    }
  }
}
