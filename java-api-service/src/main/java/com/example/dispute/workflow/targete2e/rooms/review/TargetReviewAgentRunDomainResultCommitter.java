package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.sql.Connection;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** Target-only registry entry. It persists only a Review advisory projection, never a decision. */
public final class TargetReviewAgentRunDomainResultCommitter implements AgentRunDomainResultCommitter {
  private final DataSource dataSource;
  private final TargetReviewFinalizationRequestResolver requestResolver;
  private final TargetReviewFinalizationAdapter finalizer;
  public TargetReviewAgentRunDomainResultCommitter(DataSource dataSource,
      TargetReviewFinalizationRequestResolver requestResolver, TargetReviewFinalizationAdapter finalizer) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.requestResolver = Objects.requireNonNull(requestResolver, "requestResolver");
    this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
  }
  @Override public boolean supports(RoomType roomType, String graphKey) {
    return roomType == RoomType.REVIEW && TargetReviewFinalizationAdapter.TARGET_GRAPH_KEY.equals(graphKey);
  }
  @Override public CommitReceipt commit(CommitCommand command) {
    TargetReviewFinalizationRequest request = Objects.requireNonNull(requestResolver.resolve(command), "resolver result");
    if (!supports(command.request().command().roomType(), command.request().command().graphKey())
        || !command.request().equals(request.request()) || !command.result().equals(request.result())
        || !command.result().resultHash().equals(command.manifest().output().sha256())) {
      throw new IllegalArgumentException("target Review advisory finalization binding is invalid");
    }
    Connection transaction = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(transaction, dataSource) || transaction.getAutoCommit()) {
        throw new IllegalStateException("target Review formal commit requires the outer transaction");
      }
      var committed = finalizer.finalizeInTransaction(transaction, request);
      var graph = command.request().command();
      return new CommitReceipt(committed.formalObjectId(), graph.caseId(), graph.roomEpoch(), graph.processRevision(),
          graph.stageCode(), graph.stageSequence(), graph.actorScope().actorId(), graph.actorScope().actorRole(), graph.actorScope().audience(),
          request.roomFencingToken(), command.result().resultHash());
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("target Review transaction inspection failed", failure);
    } finally { DataSourceUtils.releaseConnection(transaction, dataSource); }
  }
}
