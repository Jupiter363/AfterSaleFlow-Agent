package com.example.dispute.workflow.activity.system;

import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.IntakeInfrastructurePreparationResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** Executes no domain workflow or Graph command; it only invokes the shared readiness transport. */
public final class IntakeInfrastructurePreparationWorkflowImpl
        implements IntakeInfrastructurePreparationWorkflow {

    private final TemporalWorkerProbeActivities activities =
            Workflow.newActivityStub(
                    TemporalWorkerProbeActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(20))
                            .setRetryOptions(
                                    RetryOptions.newBuilder().setMaximumAttempts(1).build())
                            .build());

    @Override
    public IntakeInfrastructurePreparationResult prepare() {
        IntakeInfrastructurePreparationResult result =
                activities.prepareIntakeInfrastructure();
        if (result == null
                || !IntakeInfrastructurePreparationResult.SCHEMA_VERSION.equals(
                        result.schemaVersion())
                || !IntakeInfrastructurePreparationResult.READY.equals(result.status())) {
            throw new IllegalStateException(
                    "Intake infrastructure preparation result was not ready");
        }
        return result;
    }
}
