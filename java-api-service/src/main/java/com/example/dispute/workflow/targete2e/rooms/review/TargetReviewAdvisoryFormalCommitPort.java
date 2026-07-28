package com.example.dispute.workflow.targete2e.rooms.review;

import java.sql.Connection;
import java.util.Objects;

/** Concrete Review formal port: validates a Java decision exists, then commits only its Graph advisory. */
public final class TargetReviewAdvisoryFormalCommitPort implements TargetReviewFormalCommitPort {
  private final TargetReviewOutcomeHandoffStore handoffStore;
  private final TargetReviewAdvisoryProjectionPort projectionPort;

  public TargetReviewAdvisoryFormalCommitPort(TargetReviewOutcomeHandoffStore handoffStore,
      TargetReviewAdvisoryProjectionPort projectionPort) {
    this.handoffStore = Objects.requireNonNull(handoffStore, "handoffStore");
    this.projectionPort = Objects.requireNonNull(projectionPort, "projectionPort");
  }

  @Override public CommitResult commit(Connection transaction, TargetReviewFinalizationRequest request) {
    Objects.requireNonNull(transaction, "transaction"); Objects.requireNonNull(request, "request");
    var graph = request.request().command();
    var handoff = handoffStore.requireInTransaction(transaction, new TargetReviewOutcomeHandoffStore.Route(
        request.activationId(), request.activationManifestHash(), graph.tenantSurrogate(), graph.caseId(),
        graph.commandId(), graph.roomEpoch(), request.roomFencingToken()));
    if (!handoff.decision().equals(request.humanDecision())) {
      throw new IllegalStateException("target Review finalization must use the pre-existing Java human decision");
    }
    var projection = projectionPort.append(transaction, request);
    return new CommitResult(projection.projectionId(), projection.projectionHash());
  }
}
