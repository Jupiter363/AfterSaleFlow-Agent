package com.example.dispute.workflow.config;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.NOTIFICATION_AND_TOOLS;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.temporal.worker")
public record TemporalWorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("CONTROL") WorkerRole role,
        @DefaultValue("NONE") VersioningMode versioningMode,
        @DefaultValue("after-sale-control") String deploymentName,
        @DefaultValue("local-dev") String buildId,
        @DefaultValue("512") int maxWorkflowThreads,
        @DefaultValue QueueCapacity caseControl,
        @DefaultValue QueueCapacity roomControl,
        @DefaultValue QueueCapacity agentExecution,
        @DefaultValue QueueCapacity notificationAndTools) {

    private static final Pattern VERSION_COMPONENT =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public TemporalWorkerProperties {
        if (role == null) {
            throw new IllegalArgumentException("Temporal worker role must be configured");
        }
        if (enabled && role == WorkerRole.API) {
            throw new IllegalArgumentException("API process role cannot enable a Temporal worker");
        }
        if (versioningMode == null) {
            throw new IllegalArgumentException(
                    "Temporal worker versioningMode must be configured");
        }
        requireVersionComponent(deploymentName, "deploymentName");
        requireVersionComponent(buildId, "buildId");
        if (maxWorkflowThreads < 64 || maxWorkflowThreads > 10_000) {
            throw new IllegalArgumentException(
                    "maxWorkflowThreads must be between 64 and 10000");
        }
        if (caseControl == null
                || roomControl == null
                || agentExecution == null
                || notificationAndTools == null) {
            throw new IllegalArgumentException("all Temporal task queue capacities are required");
        }
        if ((deploymentName + "." + buildId).length() > 255) {
            throw new IllegalArgumentException("legacy Temporal build ID exceeds 255 characters");
        }
    }

    public QueueCapacity capacity(String taskQueue) {
        return switch (taskQueue) {
            case CASE_CONTROL -> caseControl;
            case ROOM_CONTROL -> roomControl;
            case AGENT_EXECUTION -> agentExecution;
            case NOTIFICATION_AND_TOOLS -> notificationAndTools;
            default -> throw new IllegalArgumentException("unknown Temporal task queue: " + taskQueue);
        };
    }

    public String legacyBuildId() {
        return deploymentName + "." + buildId;
    }

    private static void requireVersionComponent(String value, String field) {
        if (value == null || !VERSION_COMPONENT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    public enum WorkerRole {
        API,
        CONTROL,
        AGENT
    }

    public enum VersioningMode {
        NONE,
        BUILD_ID,
        DEPLOYMENT
    }

    public record QueueCapacity(
            @DefaultValue("64") int maxConcurrentWorkflowTasks,
            @DefaultValue("32") int maxConcurrentActivities,
            @DefaultValue("4") int workflowPollers,
            @DefaultValue("4") int activityPollers,
            @DefaultValue("0") double maxActivitiesPerSecond) {

        public QueueCapacity {
            if (maxConcurrentWorkflowTasks < 1 || maxConcurrentWorkflowTasks > 10_000) {
                throw new IllegalArgumentException(
                        "maxConcurrentWorkflowTasks must be between 1 and 10000");
            }
            if (maxConcurrentActivities < 1 || maxConcurrentActivities > 10_000) {
                throw new IllegalArgumentException(
                        "maxConcurrentActivities must be between 1 and 10000");
            }
            if (workflowPollers < 2 || workflowPollers > 64) {
                throw new IllegalArgumentException("workflowPollers must be between 2 and 64");
            }
            if (activityPollers < 1 || activityPollers > 64) {
                throw new IllegalArgumentException("activityPollers must be between 1 and 64");
            }
            if (!Double.isFinite(maxActivitiesPerSecond) || maxActivitiesPerSecond < 0) {
                throw new IllegalArgumentException(
                        "maxActivitiesPerSecond must be finite and non-negative");
            }
        }
    }
}
