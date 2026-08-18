package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.room.application.RoomMessageView;
import java.sql.Connection;
import java.util.Objects;

/** Performs only the caller-transaction Evidence domain write. */
public final class TargetEvidenceFinalizationAdapter {
  public static final String TARGET_GRAPH_KEY = "all-rooms.target-e2e.v2";
  private final TargetEvidenceFormalCommitPort formalCommitPort;

  public TargetEvidenceFinalizationAdapter(TargetEvidenceFormalCommitPort formalCommitPort) {
    this.formalCommitPort = Objects.requireNonNull(formalCommitPort, "formalCommitPort");
  }

  public TargetEvidenceFormalCommitPort.CommitResult finalizeInTransaction(
      Connection transaction,
      TargetEvidenceFinalizationRequest request,
      RoomMessageView formalMessage) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(formalMessage, "formalMessage");
    var graph = request.command().request().command();
    if (!TARGET_GRAPH_KEY.equals(graph.graphKey()) || !"EVIDENCE".equals(graph.roomType().name())) {
      throw new IllegalArgumentException("target Evidence finalizer rejects non-target graph pins");
    }
    return formalCommitPort.commit(transaction, request, formalMessage);
  }
}
