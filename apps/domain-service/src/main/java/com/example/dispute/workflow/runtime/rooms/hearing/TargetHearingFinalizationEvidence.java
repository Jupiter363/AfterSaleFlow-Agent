package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.runtime.graph.ProductionRoomProposalSource;
import java.time.Instant;
import java.util.Objects;

/**
 * Evidence reloaded from durable target-lane stores for one Hearing result.
 *
 * <p>The resolver must obtain this from the admitted command, persisted Graph output and the
 * target runtime fence. It is intentionally data-only: the strategy verifies it before the
 * generic outer finalizer is allowed to call the Hearing domain committer.
 */
public record TargetHearingFinalizationEvidence(
    String activationManifestHash,
    String activationId,
    String roomId,
    String workflowId,
    String workflowRunId,
    String workflowBuildId,
    String isolatedDomainDbBindingHash,
    String commandHash,
    String commandEnvelopeHash,
    long roomFencingToken,
    String materialSha256,
    ArtifactPointer proposal,
    ProductionRoomProposalSource.Proposal proposalDescriptor,
    String proposalHash,
    String resultEnvelopeHash,
    AgentRunV2ManifestFactory.FinalizationFacts manifestFacts,
    Instant committedAt) {

  public TargetHearingFinalizationEvidence {
    requireHash(activationManifestHash, "activationManifestHash");
    if (activationId == null || !activationId.matches("p9act[.]v1[.][0-9a-f]{32}")) {
      throw new IllegalArgumentException("activationId must be a target activation identifier");
    }
    requireHash(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
    requireText(roomId, "roomId");
    requireText(workflowId, "workflowId");
    requireText(workflowRunId, "workflowRunId");
    requireText(workflowBuildId, "workflowBuildId");
    requireHash(commandHash, "commandHash");
    requireHash(commandEnvelopeHash, "commandEnvelopeHash");
    requireHash(materialSha256, "materialSha256");
    requireHash(proposalHash, "proposalHash");
    requireHash(resultEnvelopeHash, "resultEnvelopeHash");
    if (roomFencingToken < 1) {
      throw new IllegalArgumentException("roomFencingToken must be positive");
    }
    proposal = Objects.requireNonNull(proposal, "proposal");
    proposalDescriptor = Objects.requireNonNull(proposalDescriptor, "proposalDescriptor");
    if (!proposal.artifactId().equals(proposalDescriptor.proposalId())
        || !proposal.schemaVersion().equals(proposalDescriptor.payloadSchemaVersion())
        || !proposal.uri().equals(proposalDescriptor.payloadRef())
        || !proposal.sha256().equals(proposalDescriptor.payloadHash())) {
      throw new IllegalArgumentException("Hearing proposal descriptor differs from its artifact pointer");
    }
    manifestFacts = Objects.requireNonNull(manifestFacts, "manifestFacts");
    committedAt = Objects.requireNonNull(committedAt, "committedAt");
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
