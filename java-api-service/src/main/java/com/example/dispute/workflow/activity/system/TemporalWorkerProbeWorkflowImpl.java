package com.example.dispute.workflow.activity.system;

import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.TemporalWorkerDescription;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public final class TemporalWorkerProbeWorkflowImpl implements TemporalWorkerProbeWorkflow {

    private final TemporalWorkerProbeActivities activities =
            Workflow.newActivityStub(
                    TemporalWorkerProbeActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(5))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(1)
                                            .build())
                            .build());

    @Override
    public TemporalWorkerDescription probe() {
        return activities.describe();
    }
}
