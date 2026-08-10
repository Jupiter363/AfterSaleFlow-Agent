package com.example.dispute.workflow.config;

import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import java.time.Duration;
import java.util.Objects;

final class TemporalWorkerProbeActivitiesImpl implements TemporalWorkerProbeActivities {

    private final TemporalWorkerDescription description;
    private final GraphTransportBundle graphTransportBundle;

    TemporalWorkerProbeActivitiesImpl(
            TemporalWorkerProperties properties, String taskQueue) {
        this(properties, taskQueue, null);
    }

    TemporalWorkerProbeActivitiesImpl(
            TemporalWorkerProperties properties,
            String taskQueue,
            GraphTransportBundle graphTransportBundle) {
        this.description =
                new TemporalWorkerDescription(
                        "temporal-worker-description.v1",
                        properties.role().name(),
                        taskQueue,
                        properties.deploymentName(),
                        properties.buildId(),
                        properties.versioningMode().name());
        this.graphTransportBundle = graphTransportBundle;
    }

    @Override
    public TemporalWorkerDescription describe() {
        return description;
    }

    @Override
    public IntakeInfrastructurePreparationResult prepareIntakeInfrastructure() {
        GraphTransportBundle bundle = Objects.requireNonNull(
                graphTransportBundle,
                "Intake infrastructure preparation requires the AGENT Graph transport bundle");
        bundle.prepareIntakeInfrastructure(Duration.ofSeconds(20));
        return IntakeInfrastructurePreparationResult.ready();
    }
}
