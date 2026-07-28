package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eExecutionLaneVerifier;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy;
import java.time.Instant;
import java.util.List;
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
    String proposalHash = proposalHash(result);
    String resultEnvelopeHash = ContractJson.sha256Hex(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
        List.of(resolved.activationId(), resolved.commandHash(), resolved.commandEnvelopeHash(), result.resultHash())));
    return new PreparedFinalization(resolved.activationManifestHash(), new ReceiptBindings(resolved.activationId(),
        graph.tenantSurrogate(), graph.caseId(), RoomType.REVIEW, graph.roomEpoch(), resolved.roomFencingToken(),
        graph.processRevision(), graph.stageSequence(), resolved.commandHash(), resolved.commandEnvelopeHash(),
        graph.graphKey(), graph.graphVersion(), graph.checkpointSchemaVersion(), result.graphResult().checkpointId(),
        proposalHash, resultEnvelopeHash, resolved.isolatedDomainDbBindingHash(), facts.finalizedAt()), facts);
  }
  private static String proposalHash(ExecuteAgentRunResult result) {
    if (result.graphResult() == null || result.graphResult().artifactOperations().size() != 1) {
      throw new IllegalArgumentException("target Review requires exactly one advisory proposal artifact");
    }
    var operation = result.graphResult().artifactOperations().getFirst();
    if (operation.operation() != com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType.PROPOSE_PATCH) {
      throw new IllegalArgumentException("target Review result must remain proposal-only");
    }
    return ContractJson.sha256Hex(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(operation));
  }
}
