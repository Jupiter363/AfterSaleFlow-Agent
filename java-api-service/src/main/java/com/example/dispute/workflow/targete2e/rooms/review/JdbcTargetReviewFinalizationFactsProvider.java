package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.AgentRunWorkflowIds;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRuntimeContextProvider.RuntimeContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads only durable AgentRun/attempt/output rows; no Graph-provided runtime fact is trusted. */
public final class JdbcTargetReviewFinalizationFactsProvider implements TargetReviewFinalizationFactsProvider {
  private static final String SQL = """
      select run.logical_idempotency_key, run.fencing_token, run.run_status, run.finalization_status,
             run.tenant_surrogate, run.case_id, run.room_type, run.room_epoch, run.process_revision,
             run.protocol, run.executor_kind, attempt.id as attempt_id, attempt.attempt_status,
             attempt.executor_kind as attempt_executor_kind, attempt.provider, attempt.model_version,
             attempt.graph_key, attempt.graph_version, attempt.checkpoint_schema_version, attempt.checkpoint_id,
             attempt.result_hash, attempt.latency_ms, attempt.completed_at, attempt.final_frame_observed,
             snapshot.id as snapshot_id, snapshot.schema_version as snapshot_schema_version,
             snapshot.object_uri, snapshot.content_sha256
        from agent_run run
        join agent_run_attempt attempt on attempt.agent_run_id = run.id and attempt.id = ?
        join immutable_payload_snapshot snapshot
          on snapshot.tenant_surrogate = run.tenant_surrogate
         and snapshot.source_type = 'AGENT_RUN' and snapshot.source_id = run.id
       where run.id = ?
      """;
  private final JdbcTemplate jdbc;
  private final TargetE2eFinalizationRuntimeContextProvider runtime;

  public JdbcTargetReviewFinalizationFactsProvider(DataSource dataSource,
      TargetE2eFinalizationRuntimeContextProvider runtime) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override public AgentRunV2ManifestFactory.FinalizationFacts create(
      TargetReviewFinalizationRequest finalization, ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result) {
    Objects.requireNonNull(finalization, "finalization"); Objects.requireNonNull(request, "request");
    Objects.requireNonNull(result, "result");
    List<Row> rows = jdbc.query(SQL, (rs, ignored) -> row(rs), request.attemptId(), request.agentRunId());
    if (rows.size() != 1) throw new IllegalStateException("target Review finalization facts are absent or ambiguous");
    Row value = rows.getFirst(); RuntimeContext context = Objects.requireNonNull(runtime.current(), "runtime context");
    var graph = request.command();
    require(value.logicalIdempotencyKey != null && !value.logicalIdempotencyKey.isBlank(), "logical idempotency");
    require(value.fence == finalization.roomFencingToken(), "run fence");
    require(value.tenant.equals(graph.tenantSurrogate()) && value.caseId.equals(graph.caseId()), "run case scope");
    require("REVIEW".equals(value.roomType) && value.epoch == graph.roomEpoch()
        && value.processRevision == graph.processRevision(), "run room scope");
    require("agent-stream.v2".equals(value.protocol) && "TEMPORAL_ACTIVITY".equals(value.executorKind)
        && "TEMPORAL_ACTIVITY".equals(value.attemptExecutor), "AgentRun protocol");
    boolean resultReady = "RESULT_READY".equals(value.runStatus) && "UNCOMMITTED".equals(value.finalizationStatus)
        && "RESULT_READY".equals(value.attemptStatus);
    boolean completedReplay = "COMPLETED".equals(value.runStatus) && "COMMITTED".equals(value.finalizationStatus)
        && "COMPLETED".equals(value.attemptStatus);
    require(resultReady || completedReplay, "run status");
    require("UNCOMMITTED".equals(value.finalizationStatus) || "COMMITTED".equals(value.finalizationStatus), "finalization status");
    require("RESULT_READY".equals(value.attemptStatus) || "COMPLETED".equals(value.attemptStatus), "attempt status");
    require(value.attemptId.equals(request.attemptId()) && value.resultHash.equals(result.resultHash()), "attempt result");
    require(value.graphKey.equals(graph.graphKey()) && value.graphVersion.equals(graph.graphVersion())
        && value.checkpointSchema.equals(graph.checkpointSchemaVersion())
        && value.checkpointId.equals(result.graphResult().checkpointId()), "attempt graph pins");
    require(value.finalFrame && value.completedAt != null && value.latencyMs >= 0, "attempt completion");
    require(value.snapshotHash.equals(result.resultHash()) && value.snapshotUri != null && !value.snapshotUri.isBlank(),
        "immutable output snapshot");
    require(resultReady
        ? value.provider == null && value.model == null
        : finalization.executionProvider().equals(value.provider)
            && finalization.executionModel().equals(value.model),
        "execution identity");
    require(AgentRunWorkflowIds.forLogicalRun(request.logicalRunId()).equals(context.workflowId()),
        "runtime workflow id");
    ArtifactPointer output = new ArtifactPointer(value.snapshotId, value.snapshotSchema, value.snapshotUri, value.snapshotHash);
    return new AgentRunV2ManifestFactory.FinalizationFacts(value.fence, value.logicalIdempotencyKey,
        context.workflowId(), context.workflowRunId(), context.workflowBuildId(), finalization.executionProvider(),
        finalization.executionModel(),
        "urn:target-e2e:agent-manifest:" + sha256(finalization.activationId() + ':' + request.agentRunId()
            + ':' + request.attemptId() + ':' + result.resultHash()), output, List.of(), List.of(),
        value.latencyMs, value.completedAt);
  }

  private static Row row(ResultSet rs) throws SQLException {
    return new Row(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
        rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getString(10), rs.getString(11),
        rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16), rs.getString(17),
        rs.getString(18), rs.getString(19), rs.getString(20), rs.getString(21), rs.getLong(22),
        rs.getTimestamp(23).toInstant(), rs.getBoolean(24), rs.getString(25), rs.getString(26),
        rs.getString(27), rs.getString(28));
  }
  private static void require(boolean value, String binding) {
    if (!value) throw new IllegalStateException("target Review persisted finalization fact differs at " + binding);
  }
  private static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
  }
  private record Row(String logicalIdempotencyKey, long fence, String runStatus, String finalizationStatus,
      String tenant, String caseId, String roomType, long epoch, long processRevision, String protocol,
      String executorKind, String attemptId, String attemptStatus, String attemptExecutor, String provider,
      String model, String graphKey, String graphVersion, String checkpointSchema, String checkpointId,
      String resultHash, long latencyMs, Instant completedAt, boolean finalFrame, String snapshotId,
      String snapshotSchema, String snapshotUri, String snapshotHash) {}
}
