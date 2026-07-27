package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** Bounded retry policy for the read-only committed-receipt lookup. */
public final class IntakeAgentRunFinalizationReadPolicy {

  private static final Duration START_TO_CLOSE_CAP = Duration.ofMinutes(2);

  private IntakeAgentRunFinalizationReadPolicy() {}

  public static ActivityOptions options(Duration remaining) {
    if (remaining == null || remaining.isZero() || remaining.isNegative()) {
      throw new IllegalArgumentException("remaining deadline must be positive");
    }
    Duration startToClose =
        remaining.compareTo(START_TO_CLOSE_CAP) < 0 ? remaining : START_TO_CLOSE_CAP;
    return ActivityOptions.newBuilder()
        .setTaskQueue(CASE_CONTROL)
        .setScheduleToCloseTimeout(remaining)
        .setStartToCloseTimeout(startToClose)
        .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofMillis(200))
                .setMaximumInterval(Duration.ofSeconds(2))
                .setBackoffCoefficient(2.0)
                .setMaximumAttempts(3)
                .build())
        .build();
  }
}
