package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.Objects;

/** Deterministic Activity policy for the bounded Intake orchestration budget. */
public final class IntakeActivityTemporalPolicy {

  public static final Duration START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(10);
  public static final Duration SCHEDULE_TO_CLOSE_TIMEOUT = Duration.ofMinutes(15);
  public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(15);
  public static final int MAXIMUM_ACTIVITY_ATTEMPTS = 1;

  private IntakeActivityTemporalPolicy() {}

  public static ActivityOptions options(RetryBudget retryBudget) {
    return options(retryBudget, SCHEDULE_TO_CLOSE_TIMEOUT);
  }

  public static ActivityOptions options(RetryBudget retryBudget, Duration remaining) {
    Objects.requireNonNull(retryBudget, "retryBudget must not be null");
    if (remaining == null || remaining.isZero() || remaining.isNegative()) {
      throw new IllegalArgumentException("remaining Activity deadline must be positive");
    }
    Duration scheduleToClose = min(SCHEDULE_TO_CLOSE_TIMEOUT, remaining);
    Duration startToClose = min(START_TO_CLOSE_TIMEOUT, scheduleToClose);
    Duration heartbeat = min(HEARTBEAT_TIMEOUT, startToClose);
    return ActivityOptions.newBuilder()
        .setTaskQueue(AGENT_EXECUTION)
        .setStartToCloseTimeout(startToClose)
        .setScheduleToCloseTimeout(scheduleToClose)
        .setHeartbeatTimeout(heartbeat)
        .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaximumInterval(Duration.ofSeconds(30))
                // The Workflow owns the one command-wide retry pool. An Activity stub must never
                // multiply it with an independent retry loop.
                .setMaximumAttempts(MAXIMUM_ACTIVITY_ATTEMPTS)
                .setDoNotRetry(
                    IntakeActivityFailureTypes.BUSINESS,
                    IntakeActivityFailureTypes.AUTHORIZATION,
                    IntakeActivityFailureTypes.SCHEMA,
                    IntakeActivityFailureTypes.STALE_REVISION,
                    IntakeActivityFailureTypes.STALE_FENCE,
                    IntakeActivityFailureTypes.GUARDRAIL,
                    IntakeActivityFailureTypes.RETRY_BUDGET_EXHAUSTED,
                    IntakeActivityFailureTypes.UNCLASSIFIED,
                    IllegalArgumentException.class.getName())
                .build())
        .build();
  }

  private static Duration min(Duration left, Duration right) {
    return left.compareTo(right) <= 0 ? left : right;
  }
}
