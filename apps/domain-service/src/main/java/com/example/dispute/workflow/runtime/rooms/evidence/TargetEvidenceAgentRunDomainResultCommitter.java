package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.agentstream.application.AgentRunFinalizationContext;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** Production-only registry entry; it is intentionally not a Spring component in the normal artifact. */
public final class TargetEvidenceAgentRunDomainResultCommitter implements AgentRunDomainResultCommitter {
  private final DataSource dataSource;
  private final EntityManager entityManager;
  private final EvidenceAgentTurnService evidenceAgentTurnService;
  private final TargetEvidenceFinalizationRequestResolver requestResolver;
  private final TargetEvidenceFinalizationAdapter finalizer;
  private final ObjectMapper objectMapper;

  public TargetEvidenceAgentRunDomainResultCommitter(
      DataSource dataSource,
      EntityManager entityManager,
      EvidenceAgentTurnService evidenceAgentTurnService,
      TargetEvidenceFinalizationRequestResolver requestResolver,
      TargetEvidenceFinalizationAdapter finalizer,
      ObjectMapper objectMapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    this.evidenceAgentTurnService =
        Objects.requireNonNull(evidenceAgentTurnService, "evidenceAgentTurnService");
    this.requestResolver = Objects.requireNonNull(requestResolver, "requestResolver");
    this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
  }

  @Override
  public boolean supports(RoomType roomType, String graphKey) {
    return roomType == RoomType.EVIDENCE && TargetEvidenceFinalizationAdapter.TARGET_GRAPH_KEY.equals(graphKey);
  }

  @Override
  public CommitReceipt commit(CommitCommand command) {
    TargetEvidenceFinalizationRequest request = Objects.requireNonNull(requestResolver.resolve(command), "resolver result");
    if (!supports(command.request().command().roomType(), command.request().command().graphKey())
        || !command.request().command().equals(request.command().request().command())
        || !command.result().resultHash().equals(command.manifest().output().sha256())) {
      throw new IllegalArgumentException("target Evidence outer finalization binding is invalid");
    }
    var graph = command.request().command();
    var turnCommand = request.material().material().evidenceAgentTurnCommand();
    request.proposal().evidenceTurnResult().requireFormalScope(
        turnCommand.contextEnvelope().currentEvent().eventType(),
        turnCommand.contextEnvelope().currentEvent().attachmentRefs(),
        frozenFactIds(turnCommand.contextEnvelope().frozenSubmission().matrix()));
    AgentRunFinalizationContext finalization = new AgentRunFinalizationContext(
        command.request().agentRunId(),
        graph.caseId(),
        turnCommand.contextEnvelope().roomPolicy().roomId(),
        "EVIDENCE_TURN",
        graph.traceparent(),
        request.formalOperationId(),
        objectMapper.valueToTree(turnCommand));
        RoomMessageView formalMessage = Objects.requireNonNull(
            evidenceAgentTurnService.finalizeTargetResultV2(
                finalization, turnCommand, request.proposal().evidenceTurnResult()),
            "Evidence formal service returned no message");
    entityManager.flush();
    Connection transaction = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(transaction, dataSource) || transaction.getAutoCommit()) {
        throw new IllegalStateException("target Evidence formal commit requires the outer transaction");
      }
      var committed = finalizer.finalizeInTransaction(transaction, request, formalMessage);
      return new CommitReceipt(committed.formalObjectId(), graph.caseId(), graph.roomEpoch(), graph.processRevision(),
          request.stageCode(), request.stageSequence(), request.actorId(), request.actorRole(), request.audience(),
          request.command().manifest().fencingToken(), command.result().resultHash());
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("target Evidence transaction inspection failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(transaction, dataSource);
    }
  }

  private static Set<String> frozenFactIds(com.fasterxml.jackson.databind.JsonNode matrix) {
    if (matrix == null
        || !matrix.isObject()
        || !"case_fact_matrix.v2".equals(matrix.path("schema_version").asText())
        || !matrix.path("fact_rows").isArray()
        || matrix.path("fact_rows").isEmpty()) {
      throw new IllegalStateException("target Evidence v2 requires a frozen case fact matrix");
    }
    Set<String> factIds = new LinkedHashSet<>();
    for (var row : matrix.path("fact_rows")) {
      String factId = row.path("fact_id").asText("").trim();
      if (factId.isBlank() || !factIds.add(factId)) {
        throw new IllegalStateException("frozen Evidence fact ids are invalid");
      }
    }
    return Set.copyOf(factIds);
  }
}
