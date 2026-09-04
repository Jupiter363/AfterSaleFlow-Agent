package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/** Converts any missing, stale, or ambiguous durable Review fact into a fail-closed CONTROL failure. */
public final class TargetReviewOutcomeStartBindingActivity
    implements TargetReviewOutcomeStartBindingActivities {
  public static final String BINDING_INVALID = "TARGET_REVIEW_OUTCOME_START_BINDING_INVALID";

  private final TargetReviewOutcomeStartBindingPort port;

  public TargetReviewOutcomeStartBindingActivity(TargetReviewOutcomeStartBindingPort port) {
    this.port = Objects.requireNonNull(port, "port");
  }

  @Override
  public Result bind(ProvisionRoomEpoch provision) {
    try {
      if (provision == null) {
        throw new IllegalArgumentException("provision must not be null");
      }
      return new Result(port.bind(provision));
    } catch (ApplicationFailure failure) {
      throw failure;
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw ApplicationFailure.newNonRetryableFailure(failure.getMessage(), BINDING_INVALID);
    }
  }
}
