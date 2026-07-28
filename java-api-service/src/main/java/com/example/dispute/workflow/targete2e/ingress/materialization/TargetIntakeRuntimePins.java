package com.example.dispute.workflow.targete2e.ingress.materialization;

import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;

/** Frozen target activation/profile values required to build a graph-private Intake command. */
public record TargetIntakeRuntimePins(
        String caseBuildId, String agentBuildId, String graphBindingHash, String graphCodeBuildId,
        String isolatedDomainDbBindingHash, String promptVersion, String modelProfileId,
        String policyVersion, String guardrailVersion, String toolPolicyVersion,
        String memoryPolicyVersion, String envelopeKeyId) {
    public TargetIntakeRuntimePins {
        for (String value : java.util.List.of(caseBuildId, agentBuildId, graphBindingHash, graphCodeBuildId,
                isolatedDomainDbBindingHash, promptVersion, modelProfileId, policyVersion, guardrailVersion,
                toolPolicyVersion, memoryPolicyVersion, envelopeKeyId)) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("target Intake runtime pin is blank");
        }
        if (!graphBindingHash.matches("[0-9a-f]{64}") || !isolatedDomainDbBindingHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("target Intake hash pin is invalid");
        }
    }
    public IntakePrivateThreadRegistrationFactory.VersionPins registrationPins() {
        return new IntakePrivateThreadRegistrationFactory.VersionPins(
                "all-rooms.target-e2e.v1", "target-e2e-graph.2026-07-27.1",
                "target-e2e-checkpoint.v1", "intake-graph-state.v2", promptVersion,
                modelProfileId, "target-e2e-room-proposal-source.v1", policyVersion, guardrailVersion,
                toolPolicyVersion);
    }
    /** Profile values are deployment expectations; activation-owned bindings must match them exactly. */
    public TargetIntakeRuntimePins requireActivation(
            String activeCaseBuildId, String activeAgentBuildId, String graphKey,
            String graphVersion, String checkpointSchemaVersion, String activeGraphBindingHash,
            String activeGraphCodeBuildId, String activeDomainBindingHash) {
        if (!caseBuildId.equals(activeCaseBuildId)
                || !agentBuildId.equals(activeAgentBuildId)
                || !"all-rooms.target-e2e.v1".equals(graphKey)
                || !"target-e2e-graph.2026-07-27.1".equals(graphVersion)
                || !"target-e2e-checkpoint.v1".equals(checkpointSchemaVersion)
                || !graphBindingHash.equals(activeGraphBindingHash)
                || !graphCodeBuildId.equals(activeGraphCodeBuildId)
                || !isolatedDomainDbBindingHash.equals(activeDomainBindingHash)) {
            throw new IllegalStateException("target Intake profile pins differ from the active activation");
        }
        return this;
    }
}
