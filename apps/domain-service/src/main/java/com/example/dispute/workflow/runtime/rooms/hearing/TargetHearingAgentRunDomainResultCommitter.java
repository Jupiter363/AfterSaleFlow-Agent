package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.runtime.finalization.ProductionExecutionLaneVerifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** HEARING domain committer invoked only by the shared multi-room outer finalizer. */
public final class TargetHearingAgentRunDomainResultCommitter implements AgentRunDomainResultCommitter {
  private final DataSource dataSource;
  private final TargetHearingFinalizationRequestResolver requestResolver;
  private final TargetHearingFormalCommitPort formalCommitPort;

  public TargetHearingAgentRunDomainResultCommitter(
      DataSource dataSource,
      TargetHearingFinalizationRequestResolver requestResolver,
      TargetHearingFormalCommitPort formalCommitPort) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.requestResolver = Objects.requireNonNull(requestResolver, "requestResolver");
    this.formalCommitPort = Objects.requireNonNull(formalCommitPort, "formalCommitPort");
  }

  @Override
  public boolean supports(RoomType roomType, String graphKey) {
    return roomType == RoomType.HEARING && isTargetGraphKey(graphKey);
  }

  @Override
  public CommitReceipt commit(CommitCommand command) {
    Objects.requireNonNull(command, "command");
    if (!supports(command.request().command().roomType(), command.request().command().graphKey())) {
      throw new IllegalArgumentException("target Hearing committer rejects non-Hearing graph pins");
    }
    TargetHearingFinalizationRequest request = Objects.requireNonNull(
        requestResolver.resolve(command), "target Hearing resolver result");
    Connection transaction = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(transaction, dataSource) || transaction.getAutoCommit()) {
        throw new IllegalStateException("target Hearing formal commit requires the outer transaction");
      }
      TargetHearingFormalCommitPort.CommitResult committed = formalCommitPort.commit(transaction, request);
      var graph = command.request().command();
      if (!committed.formalObjectId().equals(request.formalObjectId())) {
        throw new IllegalStateException("target Hearing formal object identity changed during commit");
      }
      return new CommitReceipt(committed.formalObjectId(), graph.caseId(), graph.roomEpoch(),
          graph.processRevision(), request.stageCode(), request.stageSequence(), request.actorId(),
          request.actorRole(), request.audience(), request.material().admission().roomFencingToken(),
          command.result().resultHash());
    } catch (SQLException failure) {
      throw new IllegalStateException("target Hearing transaction inspection failed", failure);
    } finally {
      DataSourceUtils.releaseConnection(transaction, dataSource);
    }
  }

  private static boolean isTargetGraphKey(String graphKey) {
    return ProductionExecutionLaneVerifier.GRAPH_KEY.equals(graphKey);
  }
}
