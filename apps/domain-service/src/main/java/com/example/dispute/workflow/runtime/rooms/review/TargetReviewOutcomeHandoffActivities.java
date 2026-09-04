package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeReviewDecisionReceipt;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** CONTROL reads the Java-owned outbox and returns the exact receipt for the Outcome signal. */
@ActivityInterface
public interface TargetReviewOutcomeHandoffActivities {
  @ActivityMethod(name = "RelayTargetReviewOutcomeDecision")
  RelayResult relay(RelayRequest request);

  record RelayRequest(String activationId, String activationManifestHash, String tenantSurrogate, String caseId,
      String commandId, long roomEpoch, long roomFencingToken) {
    public RelayRequest {
      new TargetReviewOutcomeHandoffStore.Route(activationId, activationManifestHash, tenantSurrogate, caseId,
          commandId, roomEpoch, roomFencingToken);
    }
    TargetReviewOutcomeHandoffStore.Route route() {
      return new TargetReviewOutcomeHandoffStore.Route(activationId, activationManifestHash, tenantSurrogate, caseId,
          commandId, roomEpoch, roomFencingToken);
    }
  }

  /** A durable outbox entry validated by CONTROL, never a Graph-produced decision. */
  record RelayResult(String handoffId, String handoffHash, OutcomeReviewDecisionReceipt outcomeReceipt) {
    public RelayResult {
      if (handoffId == null || handoffId.isBlank()
          || handoffHash == null || !handoffHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target Review relay result has an invalid handoff identity");
      }
      outcomeReceipt = Objects.requireNonNull(outcomeReceipt, "outcomeReceipt");
    }
  }
}
