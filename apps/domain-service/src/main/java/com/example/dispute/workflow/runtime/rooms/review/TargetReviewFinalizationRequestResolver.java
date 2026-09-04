package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.runtime.finalization.ProductionExecutionLaneVerifier;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import java.util.Objects;

/** Rebuilds a Review finalization request solely from admitted material and a Java human receipt. */
public final class TargetReviewFinalizationRequestResolver {
  private final TargetReviewCommandMaterialStore materialStore;
  private final TargetReviewOutcomeHandoffStore handoffStore;
  private final TargetReviewReconciledFinalizationEvidenceSource evidenceSource;
  private final AgentRunAttemptRepository attempts;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  public TargetReviewFinalizationRequestResolver(
      TargetReviewCommandMaterialStore materialStore, TargetReviewOutcomeHandoffStore handoffStore,
      TargetReviewReconciledFinalizationEvidenceSource evidenceSource,
      AgentRunAttemptRepository attempts,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.materialStore = Objects.requireNonNull(materialStore, "materialStore");
    this.handoffStore = Objects.requireNonNull(handoffStore, "handoffStore");
    this.evidenceSource = Objects.requireNonNull(evidenceSource, "evidenceSource");
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
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
    require(ProductionExecutionLaneVerifier.GRAPH_VERSION.equals(graph.graphVersion()), "graph version");
    require(ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(
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
        objectMapper.valueToTree(graph))), "canonical graph command hash");
    require(result.outcome()
        == com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult.Outcome.COMPLETED,
        "completed result");
    require(result.graphResult() != null, "graph result");
    require(result.resultHash().equals(result.graphResult().outputHash()),
        "result hash");
    require(graph.commandId().equals(result.graphResult().commandId()), "result command id");
    require(graph.graphKey().equals(result.graphResult().graphKey()), "result graph key");
    require(graph.graphVersion().equals(result.graphResult().graphVersion()), "result graph version");

    String rootCommandId = attempts.findByAgentRunIdAndAttemptNo(request.agentRunId(), 1)
        .map(attempt -> attempt.getCommandId())
        .orElseThrow(() -> new IllegalStateException("target Review root AgentRun command is absent"));
    require(
        request.attemptNo() == 1
            ? rootCommandId.equals(graph.commandId())
            : !rootCommandId.equals(graph.commandId()),
        "root command lineage");
    var handoff = handoffStore.require(new TargetReviewOutcomeHandoffStore.Route(
        material.activationId(), material.activationManifestHash(), graph.tenantSurrogate(), graph.caseId(),
        rootCommandId, graph.roomEpoch(), material.roomFencingToken()));
    require(handoff.decision().decisionAuthority().equals(TargetReviewHumanDecisionReceipt.DECISION_AUTHORITY),
        "human decision authority");
    require(handoff.decision().outcomeReceipt().caseId().equals(graph.caseId()), "outcome case");
    require(handoff.decision().outcomeReceipt().epoch() == graph.roomEpoch(), "outcome epoch");
    require(handoff.decision().outcomeReceipt().fence() == material.roomFencingToken(), "outcome fence");
    var evidence = evidenceSource.resolve(snapshot, request, result);
    return new TargetReviewFinalizationRequest(material.executionLane(), material.activationId(),
        material.activationManifestHash(), admission.isolatedDomainDbBindingHash(), evidence.roomId(),
        material.roomFencingToken(), snapshot.admissionId(), rootCommandId,
        material.commandHash(), material.commandEnvelopeHash(), evidence.proposalHash(),
        evidence.resultEnvelopeHash(), evidence.executionProvider(), evidence.executionModel(),
        request, result, handoff.decision());
  }

  private static void require(boolean value, String binding) {
    if (!value) throw new IllegalStateException("target Review finalization binding differs at " + binding);
  }
}
