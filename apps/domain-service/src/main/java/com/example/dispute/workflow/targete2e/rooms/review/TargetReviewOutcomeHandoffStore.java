package com.example.dispute.workflow.targete2e.rooms.review;

import java.sql.Connection;
import java.util.Objects;

/** Read boundary for a Java-committed human decision and its durable Outcome outbox receipt. */
public interface TargetReviewOutcomeHandoffStore {
  Snapshot require(Route route);

  default Snapshot requireInTransaction(Connection transaction, Route route) {
    return require(route);
  }

  record Route(String activationId, String activationManifestHash, String tenantSurrogate, String caseId,
      String commandId, long roomEpoch, long roomFencingToken) {
    public Route {
      required(activationId, "activationId"); hash(activationManifestHash, "activationManifestHash");
      required(tenantSurrogate, "tenantSurrogate"); required(caseId, "caseId"); required(commandId, "commandId");
      if (roomEpoch < 0 || roomFencingToken < 1) throw new IllegalArgumentException("invalid Outcome handoff route");
    }
  }

  record Snapshot(String handoffId, String handoffHash, Route route, TargetReviewHumanDecisionReceipt decision) {
    public Snapshot {
      required(handoffId, "handoffId"); hash(handoffHash, "handoffHash");
      route = Objects.requireNonNull(route, "route"); decision = Objects.requireNonNull(decision, "decision");
    }
  }

  private static void required(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
  }
  private static void hash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
    }
  }
}
