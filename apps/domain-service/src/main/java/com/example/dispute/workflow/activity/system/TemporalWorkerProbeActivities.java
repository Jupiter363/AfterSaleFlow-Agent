package com.example.dispute.workflow.activity.system;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface TemporalWorkerProbeActivities {

    @ActivityMethod(name = "DescribeTemporalWorker")
    TemporalWorkerDescription describe();

    @ActivityMethod(name = "PrepareIntakeInfrastructure")
    IntakeInfrastructurePreparationResult prepareIntakeInfrastructure();

    record TemporalWorkerDescription(
            String schemaVersion,
            String role,
            String taskQueue,
            String deploymentName,
            String buildId,
            String versioningMode) {}

    record IntakeInfrastructurePreparationResult(String schemaVersion, String status) {

        public static final String SCHEMA_VERSION = "intake-infrastructure-preparation.v1";
        public static final String READY = "READY";

        public IntakeInfrastructurePreparationResult {
            if (!SCHEMA_VERSION.equals(schemaVersion) || !READY.equals(status)) {
                throw new IllegalArgumentException(
                        "Intake infrastructure preparation result is invalid");
            }
        }

        public static IntakeInfrastructurePreparationResult ready() {
            return new IntakeInfrastructurePreparationResult(SCHEMA_VERSION, READY);
        }
    }
}
