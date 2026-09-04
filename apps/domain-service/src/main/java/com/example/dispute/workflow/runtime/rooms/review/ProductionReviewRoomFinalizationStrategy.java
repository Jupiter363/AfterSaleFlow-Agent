package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionCrossRoomActivationVerifier;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort;
import com.example.dispute.workflow.runtime.finalization.ProductionExecutionLaneVerifier;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy;
import java.util.Objects;

/** Target Review strategy: Graph output is advisory and the Java human decision remains authoritative. */
public final class ProductionReviewRoomFinalizationStrategy implements ProductionRoomFinalizationStrategy {
  private final TargetReviewFinalizationRequestResolver resolver;
  private final TargetReviewFinalizationFactsProvider factsProvider;
  private final ProductionFinalizationActivationPort activation;

  public ProductionReviewRoomFinalizationStrategy(TargetReviewFinalizationRequestResolver resolver,
      TargetReviewFinalizationFactsProvider factsProvider,
      ProductionFinalizationActivationPort activation) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.factsProvider = Objects.requireNonNull(factsProvider, "factsProvider");
    this.activation = Objects.requireNonNull(activation, "activation");
  }
  @Override public RoomType roomType() { return RoomType.REVIEW; }
  @Override public boolean supports(ExecuteAgentRunRequest request) {
    return request != null && request.command().roomType() == RoomType.REVIEW
        && TargetReviewFinalizationAdapter.TARGET_GRAPH_KEY.equals(request.command().graphKey())
        && ProductionExecutionLaneVerifier.GRAPH_VERSION.equals(request.command().graphVersion())
        && ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(request.command().checkpointSchemaVersion());
  }
  @Override public PreparedFinalization prepare(ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
    if (!supports(request)) throw new IllegalArgumentException("target Review strategy rejects non-target graph pins");
    TargetReviewFinalizationRequest resolved = resolver.resolve(request, result);
    var facts = factsProvider.create(resolved, request, result);
    var graph = request.command();
    var authorization = new ProductionFinalizationActivationPort.AuthorizationRequest(
        graph.tenantSurrogate(), graph.caseId(), resolved.roomId(), RoomType.REVIEW,
        request.agentRunId(), facts.workflowId(), facts.workflowRunId(), facts.workflowBuildId(),
        graph.commandId(), resolved.commandHash(), resolved.commandEnvelopeHash(), graph.roomEpoch(),
        resolved.roomFencingToken());
    ProductionCrossRoomActivationVerifier.requireAuthorized(
        activation.authorize(authorization), authorization, resolved.activationId(),
        resolved.activationManifestHash(), resolved.isolatedDomainDbBindingHash());
    return new PreparedFinalization(resolved.activationManifestHash(), new ReceiptBindings(resolved.activationId(),
        graph.tenantSurrogate(), graph.caseId(), RoomType.REVIEW, graph.roomEpoch(), resolved.roomFencingToken(),
        graph.processRevision(), graph.stageSequence(), resolved.commandHash(), resolved.commandEnvelopeHash(),
        graph.graphKey(), graph.graphVersion(), graph.checkpointSchemaVersion(), result.graphResult().checkpointId(),
        resolved.proposalHash(), resolved.resultEnvelopeHash(), resolved.isolatedDomainDbBindingHash(), facts.finalizedAt()), facts);
  }
}
