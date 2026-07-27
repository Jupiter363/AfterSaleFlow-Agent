package com.example.dispute.workflow.targete2e.graph;

/** Loads the exact room proposal source document whose {@code /proposal} is hash-authoritative. */
@FunctionalInterface
public interface TargetE2EGraphProposalPayloadSource {

  byte[] loadSchemaValidatedProposalSource(String resultRef, String expectedProposalHash);
}
