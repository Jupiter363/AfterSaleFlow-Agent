package com.example.dispute.workflow.temporal.agentrun;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** Phase 2 contract-fixed Temporal execution policy. */
public final class AgentRunTemporalPolicy {

    public static final Duration START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(10);
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration PROGRESS_HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
    public static final Duration FINALIZER_START_TO_CLOSE_TIMEOUT = Duration.ofMinutes(2);
    public static final int MAXIMUM_ACTIVITY_ATTEMPTS = 3;

    private AgentRunTemporalPolicy() {}

    public static ActivityOptions activityOptions() {
        return activityOptions(MAXIMUM_ACTIVITY_ATTEMPTS);
    }

    /**
     * Applies the command's remaining infrastructure budget at Temporal's scheduling boundary.
     *
     * <p>Temporal requires at least one attempt to run the Activity. A zero budget therefore gets
     * one reconciliation-only invocation, which cannot start graph or model execution.
     */
    public static ActivityOptions activityOptions(int activityAttemptsRemaining) {
        int scheduledAttempts = Math.max(1, boundedActivityAttempts(activityAttemptsRemaining));
        return ActivityOptions.newBuilder()
                .setTaskQueue(AGENT_EXECUTION)
                .setStartToCloseTimeout(START_TO_CLOSE_TIMEOUT)
                .setHeartbeatTimeout(HEARTBEAT_TIMEOUT)
                .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                .setRetryOptions(
                        RetryOptions.newBuilder()
                                .setInitialInterval(Duration.ofSeconds(1))
                                .setBackoffCoefficient(2.0)
                                .setMaximumInterval(Duration.ofSeconds(30))
                                .setMaximumAttempts(scheduledAttempts)
                                .setDoNotRetry(
                                        IllegalArgumentException.class.getName(),
                                        "AgentRunNonRetryableFailure")
                                .build())
                .build();
    }

    public static int boundedActivityAttempts(int activityAttemptsRemaining) {
        return Math.min(MAXIMUM_ACTIVITY_ATTEMPTS, Math.max(0, activityAttemptsRemaining));
    }

    /** Finalizer retries are independent and unbounded; its domain write is idempotent and fenced. */
    public static ActivityOptions finalizerActivityOptions() {
        return ActivityOptions.newBuilder()
                .setTaskQueue(AGENT_EXECUTION)
                .setStartToCloseTimeout(FINALIZER_START_TO_CLOSE_TIMEOUT)
                .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                .setRetryOptions(
                        RetryOptions.newBuilder()
                                .setInitialInterval(Duration.ofSeconds(1))
                                .setBackoffCoefficient(2.0)
                                .setMaximumInterval(Duration.ofSeconds(30))
                                .setMaximumAttempts(0)
                                .setDoNotRetry("AgentRunFinalizationRejected")
                                .build())
                .build();
    }
}
