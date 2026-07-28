package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eExecutionLaneVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Rebuilds a Review finalization request solely from admitted material and a Java human receipt. */
public final class TargetReviewFinalizationRequestResolver {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final TargetReviewCommandMaterialStore materialStore;
  private final TargetReviewOutcomeHandoffStore handoffStore;

  public TargetReviewFinalizationRequestResolver(
      TargetReviewCommandMaterialStore materialStore, TargetReviewOutcomeHandoffStore handoffStore) {
    this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
    this.handoffStore = Objects.requireNonNull(handoffStore, "handoffStore");
  }

  public TargetReviewFinalizationRequest resolve(CommitCommand command) {
    Objects.requireNonNull(command, "command");
    return resolve(command.request(), command.result());
  }

  public TargetReviewFinalizationRequest resolve(
      com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest request,
      com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult result) {
    Objects.requireNonNull(request, "request"); Objects.requireNonNull(result, "result");
    var graph = request.command();
    require(graph.roomType() == RoomType.REVIEW, "room type");
    require(TargetReviewFinalizationAdapter.TARGET_GRAPH_KEY.equals(graph.graphKey()), "graph key");
    require(TargetE2eExecutionLaneVerifier.GRAPH_VERSION.equals(graph.graphVersion()), "graph version");
    require(TargetE2eExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(
        graph.checkpointSchemaVersion()), "checkpoint schema");
    var candidates = materialStore.readByCommand(new TargetReviewCommandMaterialStore.CommandRoute(
        graph.tenantSurrogate(), graph.caseId(), graph.commandId(), graph.roomEpoch())).stream()
        .filter(candidate -> candidate.material().request().equals(request)).toList();
    if (candidates.size() != 1) throw new IllegalStateException("target Review admitted material is absent or ambiguous");
    var snapshot = candidates.getFirst();
    var material = snapshot.material();
    var admission = snapshot.admission();
    require(material.request().equals(request), "persisted AgentRun request");
    require(material.activationId().equals(admission.activationId()), "activation admission");
    require(material.activationManifestHash().equals(admission.manifestHash()), "manifest admission");
    require(material.commandHash().equals(admission.commandHash()), "command hash admission");
    require(material.commandEnvelopeHash().equals(admission.commandEnvelopeHash()), "envelope hash admission");
    require(material.roomFencingToken() == admission.roomFencingToken(), "fence admission");
    require(material.expectedProcessRevision() == graph.processRevision(), "process revision");
    require(material.commandHash().equals(ContractJson.sha256Hex(
        JSON.valueToTree(graph))), "canonical graph command hash");
    require(result.outcome()
        == com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult.Outcome.COMPLETED,
        "completed result");
    require(result.graphResult() != null, "graph result");
    require(result.resultHash().equals(result.graphResult().outputHash()),
        "result hash");
    require(graph.commandId().equals(result.graphResult().commandId()), "result command id");
    require(graph.graphKey().equals(result.graphResult().graphKey()), "result graph key");
    require(graph.graphVersion().equals(result.graphResult().graphVersion()), "result graph version");

    var handoff = handoffStore.require(new TargetReviewOutcomeHandoffStore.Route(
        material.activationId(), material.activationManifestHash(), graph.tenantSurrogate(), graph.caseId(),
        graph.commandId(), graph.roomEpoch(), material.roomFencingToken()));
    require(handoff.decision().decisionAuthority().equals(TargetReviewHumanDecisionReceipt.DECISION_AUTHORITY),
        "human decision authority");
    require(handoff.decision().outcomeReceipt().caseId().equals(graph.caseId()), "outcome case");
    require(handoff.decision().outcomeReceipt().epoch() == graph.roomEpoch(), "outcome epoch");
    require(handoff.decision().outcomeReceipt().fence() == material.roomFencingToken(), "outcome fence");
    return new TargetReviewFinalizationRequest(material.executionLane(), material.activationId(),
        material.activationManifestHash(), admission.isolatedDomainDbBindingHash(), material.roomFencingToken(), snapshot.admissionId(),
        material.commandHash(), material.commandEnvelopeHash(), request, result, handoff.decision());
  }

  private static void require(boolean value, String binding) {
    if (!value) throw new IllegalStateException("target Review finalization binding differs at " + binding);
  }
}
