package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.sql.Connection;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** Target-only registry entry; it is intentionally not a Spring component in the normal artifact. */
public final class TargetEvidenceAgentRunDomainResultCommitter implements AgentRunDomainResultCommitter {
  private final DataSource dataSource;
  private final TargetEvidenceFinalizationRequestResolver requestResolver;
  private final TargetEvidenceFinalizationAdapter finalizer;

  public TargetEvidenceAgentRunDomainResultCommitter(
      DataSource dataSource,
      TargetEvidenceFinalizationRequestResolver requestResolver,
      TargetEvidenceFinalizationAdapter finalizer) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.requestResolver = Objects.requireNonNull(requestResolver, "requestResolver");
    this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
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
    Connection transaction = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(transaction, dataSource) || transaction.getAutoCommit()) {
        throw new IllegalStateException("target Evidence formal commit requires the outer transaction");
      }
      var committed = finalizer.finalizeInTransaction(transaction, request);
      var graph = command.request().command();
      return new CommitReceipt(committed.formalObjectId(), graph.caseId(), graph.roomEpoch(), graph.processRevision(),
          request.stageCode(), request.stageSequence(), request.actorId(), request.actorRole(), request.audience(),
          request.command().manifest().fencingToken(), command.result().resultHash());
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("target Evidence transaction inspection failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(transaction, dataSource);
    }
  }
}
