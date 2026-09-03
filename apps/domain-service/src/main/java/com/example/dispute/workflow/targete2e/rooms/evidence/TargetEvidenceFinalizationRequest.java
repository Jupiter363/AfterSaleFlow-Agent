package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore.MaterialSnapshot;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceTurnProposalLoader.LoadedProposal;
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
    CommitCommand command,
    MaterialSnapshot material,
    LoadedProposal proposal) {
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
    material = Objects.requireNonNull(material, "material");
    proposal = Objects.requireNonNull(proposal, "proposal");
    var graph = command.request().command();
    var admitted = material.material();
    if (!TargetEvidenceCommandMaterial.SCHEMA_VERSION.equals(admitted.schemaVersion())
        || admitted.evidenceAgentTurnCommand() == null
        || !admitted.request().equals(command.request())
        || !material.admissionId().equals(admissionId)
        || !proposal.commandId().equals(graph.commandId())
        || !proposal.logicalRunId().equals(graph.logicalRunId())
        || !proposal.attemptId().equals(graph.attemptId())
        || !proposal.tenantSurrogate().equals(graph.tenantSurrogate())
        || !proposal.caseId().equals(graph.caseId())
        || proposal.roomEpoch() != graph.roomEpoch()
        || proposal.fencingToken() != roomFencingToken
        || !proposal.threadId().equals(graph.threadId())
        || !proposal.actorId().equals(actorId)
        || !proposal.actorRole().equals(actorRole.name())
        || !proposal.inputHash().equals(graph.domainSnapshotRef().sha256())
        || !proposal.roomUtterance().equals(proposal.evidenceTurnResult().roomUtterance())) {
      throw new IllegalArgumentException("target Evidence material or proposal binding is invalid");
    }
  }
}
