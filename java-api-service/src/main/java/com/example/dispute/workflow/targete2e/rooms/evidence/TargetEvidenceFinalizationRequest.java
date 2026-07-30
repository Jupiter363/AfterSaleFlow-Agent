package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import java.util.Objects;

/** Exact target-lane bindings required before Java writes formal Evidence facts. */
public record TargetEvidenceFinalizationRequest(
    String executionLane,
    String activationId,
    String activationManifestHash,
    String admissionId,
    String isolatedDomainDbBindingHash,
    String commandHash,
    String commandEnvelopeHash,
    String caseCommandRequestHash,
    long roomFencingToken,
    long expectedProcessRevision,
    long expectedRoomRevision,
    String formalOperationId,
    String stageCode,
    long stageSequence,
    String actorId,
    ActorRole actorRole,
    Audience audience,
    CommitCommand command) {
  public TargetEvidenceFinalizationRequest {
    if (!TargetEvidenceCommandMaterial.TARGET_LANE.equals(executionLane)
        || activationId == null || activationId.isBlank() || admissionId == null || admissionId.isBlank()
        || activationManifestHash == null || !activationManifestHash.matches("[0-9a-f]{64}")
        || isolatedDomainDbBindingHash == null
        || !isolatedDomainDbBindingHash.matches("[0-9a-f]{64}")
        || commandHash == null || !commandHash.matches("[0-9a-f]{64}")
        || commandEnvelopeHash == null || !commandEnvelopeHash.matches("[0-9a-f]{64}")
        || caseCommandRequestHash == null || !caseCommandRequestHash.matches("[0-9a-f]{64}")
        || roomFencingToken < 1 || expectedProcessRevision < 0 || expectedRoomRevision < 0
        || formalOperationId == null || formalOperationId.isBlank()
        || stageCode == null || stageCode.isBlank() || stageSequence < 0
        || actorId == null || actorId.isBlank() || actorRole == null || audience == null) {
      throw new IllegalArgumentException("target Evidence finalization request is invalid");
    }
    command = Objects.requireNonNull(command, "command");
  }
}
