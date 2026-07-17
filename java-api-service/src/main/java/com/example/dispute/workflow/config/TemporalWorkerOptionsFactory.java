package com.example.dispute.workflow.config;

import static io.temporal.common.VersioningBehavior.PINNED;

import com.example.dispute.workflow.config.TemporalWorkerProperties.QueueCapacity;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import org.springframework.stereotype.Component;

@Component
public final class TemporalWorkerOptionsFactory {

    private final TemporalWorkerProperties properties;

    public TemporalWorkerOptionsFactory(TemporalWorkerProperties properties) {
        this.properties = properties;
    }

    public WorkerFactoryOptions factoryOptions() {
        return WorkerFactoryOptions.newBuilder()
                .setMaxWorkflowThreadCount(properties.maxWorkflowThreads())
                .setEnableLoggingInReplay(false)
                .build();
    }

    public WorkerOptions workerOptions(String taskQueue) {
        QueueCapacity capacity = properties.capacity(taskQueue);
        return workerOptions(capacity);
    }

    public WorkerOptions legacyControlWorkerOptions() {
        return workerOptions(properties.caseControl());
    }

    private WorkerOptions workerOptions(QueueCapacity capacity) {
        WorkerOptions.Builder builder =
                WorkerOptions.newBuilder()
                        .setMaxConcurrentWorkflowTaskExecutionSize(
                                capacity.maxConcurrentWorkflowTasks())
                        .setMaxConcurrentActivityExecutionSize(
                                capacity.maxConcurrentActivities())
                        .setMaxConcurrentWorkflowTaskPollers(capacity.workflowPollers())
                        .setMaxConcurrentActivityTaskPollers(capacity.activityPollers())
                        .setUsingVirtualThreadsOnActivityWorker(true);
        if (capacity.maxActivitiesPerSecond() > 0) {
            builder.setMaxWorkerActivitiesPerSecond(capacity.maxActivitiesPerSecond());
        }
        configureVersioning(builder);
        return builder.build();
    }

    @SuppressWarnings("deprecation")
    private void configureVersioning(WorkerOptions.Builder builder) {
        switch (properties.versioningMode()) {
            case NONE -> {
                // Local and bootstrap clusters may deliberately run without routing versioning.
            }
            case BUILD_ID ->
                    builder.setBuildId(properties.legacyBuildId())
                            .setUseBuildIdForVersioning(true);
            case DEPLOYMENT ->
                    builder.setDeploymentOptions(
                            WorkerDeploymentOptions.newBuilder()
                                    .setUseVersioning(true)
                                    .setVersion(
                                            new WorkerDeploymentVersion(
                                                    properties.deploymentName(),
                                                    properties.buildId()))
                                    .setDefaultVersioningBehavior(PINNED)
                                    .build());
        }
    }
}
