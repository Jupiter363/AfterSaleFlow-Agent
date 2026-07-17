package com.example.dispute.workflow.activity.system;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface TemporalWorkerProbeActivities {

    @ActivityMethod(name = "DescribeTemporalWorker")
    TemporalWorkerDescription describe();

    record TemporalWorkerDescription(
            String schemaVersion,
            String role,
            String taskQueue,
            String deploymentName,
            String buildId,
            String versioningMode) {}
}
