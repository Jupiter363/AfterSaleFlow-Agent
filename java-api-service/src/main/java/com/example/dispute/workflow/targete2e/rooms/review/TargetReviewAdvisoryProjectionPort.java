package com.example.dispute.workflow.targete2e.rooms.review;

import java.sql.Connection;

/** Append-only projection of a Graph recommendation. It is not a review decision or an Outcome command. */
@FunctionalInterface
public interface TargetReviewAdvisoryProjectionPort {
  ProjectionReceipt append(Connection transaction, TargetReviewFinalizationRequest request);

  record ProjectionReceipt(String projectionId, String projectionHash) {
    public ProjectionReceipt {
      if (projectionId == null || projectionId.isBlank() || projectionHash == null
          || !projectionHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target Review advisory projection receipt is invalid");
      }
    }
  }
}
