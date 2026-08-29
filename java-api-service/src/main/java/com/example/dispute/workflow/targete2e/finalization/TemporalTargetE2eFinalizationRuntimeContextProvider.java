package com.example.dispute.workflow.targete2e.finalization;

import io.temporal.activity.Activity;

/** Reads Workflow/Run identity from the active Temporal Activity and the pinned worker build. */
public final class TemporalTargetE2eFinalizationRuntimeContextProvider
        implements TargetE2eFinalizationRuntimeContextProvider {

    private final String workflowBuildId;
    private final String activationId;
    private final String activationManifestHash;
    private final String isolatedDomainDbBindingHash;

    public TemporalTargetE2eFinalizationRuntimeContextProvider(
            String workflowBuildId,
            String activationId,
            String activationManifestHash,
            String isolatedDomainDbBindingHash) {
        if (workflowBuildId == null || workflowBuildId.isBlank()) {
            throw new IllegalArgumentException("workflowBuildId is required");
        }
        if (activationId == null || !activationId.matches("p9act[.]v1[.][0-9a-f]{32}")) {
            throw new IllegalArgumentException("activationId is invalid");
        }
        requireHash(activationManifestHash, "activationManifestHash");
        requireHash(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
        this.workflowBuildId = workflowBuildId;
        this.activationId = activationId;
        this.activationManifestHash = activationManifestHash;
        this.isolatedDomainDbBindingHash = isolatedDomainDbBindingHash;
    }

    @Override
    public RuntimeContext current() {
        var info = Activity.getExecutionContext().getInfo();
        return new RuntimeContext(
                info.getWorkflowId(),
                info.getRunId(),
                workflowBuildId,
                activationId,
                activationManifestHash,
                isolatedDomainDbBindingHash);
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
    }
}
