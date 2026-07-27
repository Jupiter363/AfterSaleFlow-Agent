package com.example.dispute.workflow.targete2e.finalization;

import io.temporal.activity.Activity;

/** Reads Workflow/Run identity from the active Temporal Activity and the pinned worker build. */
public final class TemporalTargetE2eFinalizationRuntimeContextProvider
        implements TargetE2eFinalizationRuntimeContextProvider {

    private final String workflowBuildId;

    public TemporalTargetE2eFinalizationRuntimeContextProvider(String workflowBuildId) {
        if (workflowBuildId == null || workflowBuildId.isBlank()) {
            throw new IllegalArgumentException("workflowBuildId is required");
        }
        this.workflowBuildId = workflowBuildId;
    }

    @Override
    public RuntimeContext current() {
        var info = Activity.getExecutionContext().getInfo();
        return new RuntimeContext(info.getWorkflowId(), info.getRunId(), workflowBuildId);
    }
}
