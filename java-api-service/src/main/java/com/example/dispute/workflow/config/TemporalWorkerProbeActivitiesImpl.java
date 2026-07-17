package com.example.dispute.workflow.config;

import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities;

final class TemporalWorkerProbeActivitiesImpl implements TemporalWorkerProbeActivities {

    private final TemporalWorkerDescription description;

    TemporalWorkerProbeActivitiesImpl(
            TemporalWorkerProperties properties, String taskQueue) {
        this.description =
                new TemporalWorkerDescription(
                        "temporal-worker-description.v1",
                        properties.role().name(),
                        taskQueue,
                        properties.deploymentName(),
                        properties.buildId(),
                        properties.versioningMode().name());
    }

    @Override
    public TemporalWorkerDescription describe() {
        return description;
    }
}
