package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;

/** Loads the exact room proposal source document whose {@code /proposal} is hash-authoritative. */
@FunctionalInterface
public interface ProductionGraphProposalPayloadSource {

  byte[] loadSchemaValidatedProposalSource(
      ProductionSealedGraphCommand command,
      String resultRef,
      String expectedProposalHash,
      AgentRunCancellationToken cancellationToken);
}
