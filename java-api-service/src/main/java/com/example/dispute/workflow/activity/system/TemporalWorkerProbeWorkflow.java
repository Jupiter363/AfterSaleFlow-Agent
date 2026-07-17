package com.example.dispute.workflow.activity.system;

import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.TemporalWorkerDescription;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface TemporalWorkerProbeWorkflow {

    @WorkflowMethod(name = "TemporalWorkerProbeWorkflow")
    TemporalWorkerDescription probe();
}
