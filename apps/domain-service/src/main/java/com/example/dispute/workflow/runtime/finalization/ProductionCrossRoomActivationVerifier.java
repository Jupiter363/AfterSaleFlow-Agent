package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AcceptedCommandProof;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.ActivationGrant;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AuthorizationDecision;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.AuthorizationRequest;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.Decision;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationActivationPort.Lifecycle;
import java.util.Objects;

/** Shared fail-closed validation for non-Intake target finalization activation grants. */
public final class ProductionCrossRoomActivationVerifier {

  private ProductionCrossRoomActivationVerifier() {}

  public static ActivationGrant requireAuthorized(
      AuthorizationDecision decision,
      AuthorizationRequest request,
      String expectedActivationId,
      String expectedActivationManifestHash,
      String expectedIsolatedDomainDbBindingHash) {
    Objects.requireNonNull(request, "request");
    if (decision == null || decision.decision() != Decision.ALLOWED || decision.grant() == null) {
      throw rejected(
          "PRODUCTION_RUNTIME_ACTIVATION_DENIED",
          "target room finalization has no current allowed activation");
    }
    ActivationGrant grant = decision.grant();
    if (!expectedActivationId.equals(grant.activationId())
        || !ProductionExecutionLaneVerifier.EXECUTION_LANE.equals(grant.executionLane())
        || !request.tenantSurrogate().equals(grant.tenantSurrogate())
        || !grant.allowedCaseIds().contains(request.caseId())
        || !grant.allowedRoomTypes().contains(request.roomType())
        || !request.workflowBuildId().equals(grant.expectedAgentBuildId())
        || !ProductionExecutionLaneVerifier.GRAPH_KEY.equals(grant.graphKey())
        || !ProductionExecutionLaneVerifier.GRAPH_VERSION.equals(grant.graphVersion())
        || !ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(
            grant.checkpointSchemaVersion())
        || !expectedActivationManifestHash.equals(grant.activationManifestHash())
        || !expectedIsolatedDomainDbBindingHash.equals(grant.isolatedDomainDbBindingHash())) {
      throw rejected(
          "PRODUCTION_RUNTIME_ACTIVATION_BINDING_MISMATCH",
          "target room activation grant differs from durable finalization authority");
    }
    if (grant.revokedAt() != null || grant.lifecycle() == Lifecycle.REVOKED_TERMINAL) {
      throw rejected("PRODUCTION_RUNTIME_ACTIVATION_REVOKED", "target room activation is revoked");
    }
    if (grant.lifecycle() == Lifecycle.ACTIVE) {
      return grant;
    }
    if (grant.lifecycle() == Lifecycle.DRAIN_ONLY) {
      requireExactDrainProof(grant, request);
      return grant;
    }
    throw rejected(
        "PRODUCTION_RUNTIME_ACTIVATION_DRAINED",
        "target room activation cannot finalize in lifecycle " + grant.lifecycle());
  }

  private static void requireExactDrainProof(ActivationGrant grant, AuthorizationRequest request) {
    AcceptedCommandProof proof = grant.acceptedCommandProof();
    if (proof == null
        || proof.admittedAt().isBefore(grant.issuedAt())
        || !proof.admittedAt().isBefore(grant.expiresAt())
        || !proof.commandId().equals(request.commandId())
        || !proof.commandHash().equals(request.commandHash())
        || !proof.commandEnvelopeHash().equals(request.commandEnvelopeHash())
        || proof.roomEpoch() != request.roomEpoch()
        || proof.roomFencingToken() != request.roomFencingToken()) {
      throw rejected(
          "PRODUCTION_RUNTIME_DRAIN_PROOF_MISMATCH",
          "target room finalization is not exact pre-cutoff accepted work");
    }
  }

  private static ProductionFinalizationRejectedException rejected(String code, String message) {
    return new ProductionFinalizationRejectedException(code, message);
  }
}
