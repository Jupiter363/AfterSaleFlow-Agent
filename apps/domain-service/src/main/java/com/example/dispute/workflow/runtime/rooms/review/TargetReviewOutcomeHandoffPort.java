package com.example.dispute.workflow.runtime.rooms.review;

import java.sql.Connection;

/**
 * Java-owned durable handoff/outbox for the Outcome workflow. Implementations persist the exact
 * human-decision receipt in the caller transaction; a relay may signal Outcome only from that fact.
 */
@FunctionalInterface
public interface TargetReviewOutcomeHandoffPort {
  HandoffReceipt record(Connection transaction, TargetReviewHumanDecisionReceipt decision);

  record HandoffReceipt(String handoffId, String handoffHash) {
    public HandoffReceipt {
      if (handoffId == null || handoffId.isBlank() || handoffHash == null || !handoffHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target Review Outcome handoff receipt is invalid");
      }
    }
  }
}
