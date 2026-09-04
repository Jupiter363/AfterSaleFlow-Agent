package com.example.dispute.workflow.runtime.rooms.review;

import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/** Idempotent read/relay: it returns only a receipt previously committed by the Java decision path. */
public final class TargetReviewOutcomeHandoffActivity implements TargetReviewOutcomeHandoffActivities {
  public static final String RELAY_INVALID = "TARGET_REVIEW_OUTCOME_HANDOFF_INVALID";
  private final TargetReviewOutcomeHandoffStore store;

  public TargetReviewOutcomeHandoffActivity(TargetReviewOutcomeHandoffStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override public TargetReviewOutcomeHandoffActivities.RelayResult relay(
      RelayRequest request) {
    try {
      var input = Objects.requireNonNull(request, "request");
      var handoff = store.require(input.route());
      var receipt = handoff.decision().outcomeReceipt();
      if (!handoff.route().caseId().equals(receipt.caseId()) || handoff.route().roomEpoch() != receipt.epoch()
          || handoff.route().roomFencingToken() != receipt.fence()) {
        throw new IllegalStateException("target Review relay receipt conflicts with the durable handoff fence");
      }
      return new TargetReviewOutcomeHandoffActivities.RelayResult(
          handoff.handoffId(), handoff.handoffHash(), receipt);
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), RELAY_INVALID);
    }
  }
}
