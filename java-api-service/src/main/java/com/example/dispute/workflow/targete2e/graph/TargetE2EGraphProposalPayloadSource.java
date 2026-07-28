package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;

/** Loads the exact room proposal source document whose {@code /proposal} is hash-authoritative. */
@FunctionalInterface
public interface TargetE2EGraphProposalPayloadSource {

  byte[] loadSchemaValidatedProposalSource(
      TargetE2ESealedGraphCommand command,
      String resultRef,
      String expectedProposalHash,
      AgentRunCancellationToken cancellationToken);
}
