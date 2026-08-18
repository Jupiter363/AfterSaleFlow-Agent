package com.example.dispute.workflow.targete2e.rooms.review;

import java.sql.Connection;
import java.util.Objects;

/** Writes only the Java-owned advisory projection in the caller's outer Finalizer transaction. */
public final class TargetReviewFinalizationAdapter {
  public static final String TARGET_GRAPH_KEY = "all-rooms.target-e2e.v2";
  private final TargetReviewFormalCommitPort formalCommitPort;
  public TargetReviewFinalizationAdapter(TargetReviewFormalCommitPort formalCommitPort) {
    this.formalCommitPort = Objects.requireNonNull(formalCommitPort, "formalCommitPort");
  }
  public TargetReviewFormalCommitPort.CommitResult finalizeInTransaction(Connection transaction,
      TargetReviewFinalizationRequest request) {
    Objects.requireNonNull(transaction, "transaction"); Objects.requireNonNull(request, "request");
    var graph = request.request().command();
    if (!TARGET_GRAPH_KEY.equals(graph.graphKey()) || !"REVIEW".equals(graph.roomType().name())) {
      throw new IllegalArgumentException("target Review finalizer rejects non-target graph pins");
    }
    return formalCommitPort.commit(transaction, request);
  }
}
