package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

/** Re-reads and authenticates the target Graph result envelope before Review finalization. */
@FunctionalInterface
public interface TargetReviewReconciledFinalizationEvidenceSource {
  Evidence resolve(TargetReviewCommandMaterialStore.Snapshot material,
      ExecuteAgentRunRequest request, ExecuteAgentRunResult result);

  record Evidence(String proposalHash, String resultEnvelopeHash, String executionProvider,
      String executionModel) {
    public Evidence {
      hash(proposalHash, "proposalHash");
      hash(resultEnvelopeHash, "resultEnvelopeHash");
      required(executionProvider, "executionProvider");
      required(executionModel, "executionModel");
    }

    private static void hash(String value, String field) {
      if (value == null || !value.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
      }
    }

    private static void required(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " is required");
      }
    }
  }
}
