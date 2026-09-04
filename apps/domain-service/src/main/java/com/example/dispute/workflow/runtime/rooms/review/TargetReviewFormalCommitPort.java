package com.example.dispute.workflow.runtime.rooms.review;

import java.sql.Connection;

/**
 * Production-only Java authority port. It writes a graph advisory projection in the supplied
 * transaction after validating the already-persisted human review decision and Outcome handoff.
 * It must never derive, alter, or execute a decision from graph output.
 */
@FunctionalInterface
public interface TargetReviewFormalCommitPort {
  CommitResult commit(Connection transaction, TargetReviewFinalizationRequest request);

  record CommitResult(String formalObjectId, String completionHash) {
    public CommitResult {
      if (formalObjectId == null || formalObjectId.isBlank() || completionHash == null
          || !completionHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target Review formal commit receipt is invalid");
      }
    }
  }
}
