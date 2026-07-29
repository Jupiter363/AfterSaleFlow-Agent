package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eExecutionLaneVerifier;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy;
import java.time.Instant;
import java.util.Objects;

/** Target Review strategy: Graph output is advisory and the Java human decision remains authoritative. */
public final class TargetE2eReviewRoomFinalizationStrategy implements TargetE2eRoomFinalizationStrategy {
  private final TargetReviewFinalizationRequestResolver resolver;
  private final TargetReviewFinalizationFactsProvider factsProvider;

  public TargetE2eReviewRoomFinalizationStrategy(TargetReviewFinalizationRequestResolver resolver,
      TargetReviewFinalizationFactsProvider factsProvider) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.factsProvider = Objects.requireNonNull(factsProvider, "factsProvider");
  }
  @Override public RoomType roomType() { return RoomType.REVIEW; }
  @Override public boolean supports(ExecuteAgentRunRequest request) {
    return request != null && request.command().roomType() == RoomType.REVIEW
        && TargetReviewFinalizationAdapter.TARGET_GRAPH_KEY.equals(request.command().graphKey())
        && TargetE2eExecutionLaneVerifier.GRAPH_VERSION.equals(request.command().graphVersion())
        && TargetE2eExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(request.command().checkpointSchemaVersion());
  }
  @Override public PreparedFinalization prepare(ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
    if (!supports(request)) throw new IllegalArgumentException("target Review strategy rejects non-target graph pins");
    TargetReviewFinalizationRequest resolved = resolver.resolve(request, result);
    var facts = factsProvider.create(resolved, request, result);
    var graph = request.command();
    return new PreparedFinalization(resolved.activationManifestHash(), new ReceiptBindings(resolved.activationId(),
        graph.tenantSurrogate(), graph.caseId(), RoomType.REVIEW, graph.roomEpoch(), resolved.roomFencingToken(),
        graph.processRevision(), graph.stageSequence(), resolved.commandHash(), resolved.commandEnvelopeHash(),
        graph.graphKey(), graph.graphVersion(), graph.checkpointSchemaVersion(), result.graphResult().checkpointId(),
        resolved.proposalHash(), resolved.resultEnvelopeHash(), resolved.isolatedDomainDbBindingHash(), facts.finalizedAt()), facts);
  }
}
