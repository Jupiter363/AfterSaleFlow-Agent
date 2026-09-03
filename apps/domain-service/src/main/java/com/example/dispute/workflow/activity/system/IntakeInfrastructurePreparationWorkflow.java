package com.example.dispute.workflow.activity.system;

import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.IntakeInfrastructurePreparationResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** System-only workflow that prepares reusable Intake infrastructure on the AGENT worker. */
@WorkflowInterface
public interface IntakeInfrastructurePreparationWorkflow {

    String WORKFLOW_TYPE = "IntakeInfrastructurePreparationWorkflow";

    @WorkflowMethod(name = WORKFLOW_TYPE)
    IntakeInfrastructurePreparationResult prepare();
}
