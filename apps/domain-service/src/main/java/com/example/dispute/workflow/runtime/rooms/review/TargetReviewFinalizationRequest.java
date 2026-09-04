package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Objects;

/** Bindings required before an advisory Review AgentRun can acknowledge its Java-owned decision. */
public record TargetReviewFinalizationRequest(
    String executionLane, String activationId, String activationManifestHash, String isolatedDomainDbBindingHash,
    String roomId, long roomFencingToken, String admissionId, String rootCommandId,
    String commandHash,
    String commandEnvelopeHash, String proposalHash, String resultEnvelopeHash,
    String executionProvider, String executionModel,
    ExecuteAgentRunRequest request, ExecuteAgentRunResult result,
    TargetReviewHumanDecisionReceipt humanDecision) {
  public TargetReviewFinalizationRequest {
    if (!TargetReviewCommandMaterial.TARGET_LANE.equals(executionLane) || activationId == null || activationId.isBlank()
        || activationManifestHash == null || !activationManifestHash.matches("[0-9a-f]{64}")
        || isolatedDomainDbBindingHash == null || !isolatedDomainDbBindingHash.matches("[0-9a-f]{64}")
        || roomId == null || roomId.isBlank()
        || roomFencingToken < 1
        || admissionId == null || admissionId.isBlank()
        || rootCommandId == null || rootCommandId.isBlank()
        || commandHash == null || !commandHash.matches("[0-9a-f]{64}")
        || commandEnvelopeHash == null || !commandEnvelopeHash.matches("[0-9a-f]{64}")
        || proposalHash == null || !proposalHash.matches("[0-9a-f]{64}")
        || resultEnvelopeHash == null || !resultEnvelopeHash.matches("[0-9a-f]{64}")
        || executionProvider == null || executionProvider.isBlank()
        || executionModel == null || executionModel.isBlank()) {
      throw new IllegalArgumentException("target Review finalization request is invalid");
    }
    request = Objects.requireNonNull(request, "request");
    result = Objects.requireNonNull(result, "result");
    humanDecision = Objects.requireNonNull(humanDecision, "humanDecision");
    boolean initial = request.attemptNo() == 1;
    if (initial
        ? !rootCommandId.equals(request.command().commandId())
        : rootCommandId.equals(request.command().commandId())) {
      throw new IllegalArgumentException(
          "target Review root and winning command lineage is invalid");
    }
  }
}
