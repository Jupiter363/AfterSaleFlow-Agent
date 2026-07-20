package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** Deterministic Activity policy for the bounded Intake orchestration budget. */
public final class IntakeActivityTemporalPolicy {

  public static final Duration START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(10);
  public static final Duration SCHEDULE_TO_CLOSE_TIMEOUT = Duration.ofMinutes(15);
  public static final int MAXIMUM_ACTIVITY_ATTEMPTS = 3;

  private IntakeActivityTemporalPolicy() {}

  public static ActivityOptions options(RetryBudget retryBudget) {
    int attempts =
        Math.max(
            1,
            Math.min(
                MAXIMUM_ACTIVITY_ATTEMPTS, retryBudget.activityAttemptsRemaining()));
    return ActivityOptions.newBuilder()
        .setTaskQueue(AGENT_EXECUTION)
        .setStartToCloseTimeout(START_TO_CLOSE_TIMEOUT)
        .setScheduleToCloseTimeout(SCHEDULE_TO_CLOSE_TIMEOUT)
        .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaximumInterval(Duration.ofSeconds(30))
                .setMaximumAttempts(attempts)
                .setDoNotRetry(
                    IntakeActivityFailureTypes.BUSINESS,
                    IntakeActivityFailureTypes.AUTHORIZATION,
                    IntakeActivityFailureTypes.SCHEMA,
                    IntakeActivityFailureTypes.STALE_REVISION,
                    IntakeActivityFailureTypes.STALE_FENCE,
                    IntakeActivityFailureTypes.GUARDRAIL,
                    IllegalArgumentException.class.getName())
                .build())
        .build();
  }
}
