package com.example.dispute.workflow.targete2e.rooms.evidence;

import java.sql.Connection;

/**
 * Target-only formal writer. The caller supplies its existing transaction connection, so formal
 * Evidence facts and the generic AgentRun manifest are one atomic outcome. Target receipts and
 * activation completion are exclusively owned by the shared outer finalizer.
 */
@FunctionalInterface
public interface TargetEvidenceFormalCommitPort {
  CommitResult commit(Connection transaction, TargetEvidenceFinalizationRequest request);

  record CommitResult(String formalObjectId, String formalCommitHash) {
    public CommitResult {
      if (formalObjectId == null || formalObjectId.isBlank()
          || formalCommitHash == null || !formalCommitHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target Evidence formal commit receipt is invalid");
      }
    }
  }
}
