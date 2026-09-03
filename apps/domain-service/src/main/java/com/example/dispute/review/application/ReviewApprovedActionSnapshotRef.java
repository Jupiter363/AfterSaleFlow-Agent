package com.example.dispute.review.application;

import com.example.dispute.domain.model.ApprovalDecisionType;
import java.util.Objects;

/** Canonical reference identity for the action snapshot authorized by a human Review decision. */
final class ReviewApprovedActionSnapshotRef {

  private ReviewApprovedActionSnapshotRef() {}

  static String resolve(
      ApprovalDecisionType decision,
      String approvalId,
      String frozenActionRef,
      String frozenActionHash,
      String approvedActionHash) {
    Objects.requireNonNull(decision, "decision");
    if (decision != ApprovalDecisionType.APPROVE
        && decision != ApprovalDecisionType.MODIFY_AND_APPROVE) {
      throw new IllegalArgumentException("an approving Review decision is required");
    }
    requireComponent(approvalId, "approvalId");
    requireComponent(frozenActionRef, "frozenActionRef");
    requireHash(frozenActionHash, "frozenActionHash");
    requireHash(approvedActionHash, "approvedActionHash");
    if (decision == ApprovalDecisionType.APPROVE
        && !frozenActionHash.equals(approvedActionHash)) {
      throw new IllegalArgumentException("APPROVE must preserve the frozen action snapshot");
    }
    return frozenActionHash.equals(approvedActionHash)
        ? frozenActionRef
        : "approval:" + approvalId + ":action";
  }

  private static void requireComponent(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static void requireHash(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
    }
  }
}
