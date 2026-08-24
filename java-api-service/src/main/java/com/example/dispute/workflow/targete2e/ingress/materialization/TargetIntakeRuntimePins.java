package com.example.dispute.workflow.targete2e.ingress.materialization;

import com.example.dispute.config.ActorRole;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import java.util.Objects;

/** Frozen target activation/profile values required to build a graph-private Intake command. */
public record TargetIntakeRuntimePins(
        String caseBuildId, String agentBuildId, String graphBindingHash, String graphCodeBuildId,
        String isolatedDomainDbBindingHash, String agentProfileId, String promptVersion, String modelProfileId,
        String executionProviderId,
        String policyVersion, String guardrailVersion, String toolPolicyVersion,
        String memoryPolicyVersion, String envelopeKeyId) {
    private static final String INTAKE_AGENT_KEY = "DISPUTE_INTAKE_OFFICER";

    public TargetIntakeRuntimePins {
        for (String value : java.util.List.of(caseBuildId, agentBuildId, graphBindingHash, graphCodeBuildId,
                isolatedDomainDbBindingHash, agentProfileId, promptVersion, modelProfileId, policyVersion,
                executionProviderId, guardrailVersion, toolPolicyVersion, memoryPolicyVersion, envelopeKeyId)) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("target Intake runtime pin is blank");
        }
        if (!graphBindingHash.matches("[0-9a-f]{64}") || !isolatedDomainDbBindingHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("target Intake hash pin is invalid");
        }
    }
    public IntakePrivateThreadRegistrationFactory.VersionPins registrationPins() {
        return registrationPins(promptVersion);
    }

    /**
     * Issues the actor-private PromptComposer profile used by the baseline Intake officer.
     *
     * <p>The target activation's {@link #promptVersion()} is a room-level execution-lane pin. It
     * is intentionally not a PromptComposer profile: private Intake registrations must instead
     * carry the exact baseline profile for their authoritative party role.
     */
    public IntakePrivateThreadRegistrationFactory.VersionPins registrationPins(ActorRole actorRole) {
        return registrationPins(baselinePromptProfile(actorRole));
    }

    public static String baselinePromptProfile(ActorRole actorRole) {
        Objects.requireNonNull(actorRole, "actorRole");
        return switch (actorRole) {
            case USER, MERCHANT -> INTAKE_AGENT_KEY + ":" + actorRole.name() + ":v1";
            default -> throw new IllegalArgumentException(
                    "target Intake Prompt profile is defined only for USER or MERCHANT actors");
        };
    }

    private IntakePrivateThreadRegistrationFactory.VersionPins registrationPins(String profilePromptVersion) {
        return new IntakePrivateThreadRegistrationFactory.VersionPins(
                "all-rooms.target-e2e.v2", "target-e2e-graph.2026-08-18.1",
                "target-e2e-checkpoint.v2", "intake-graph-state.v2", profilePromptVersion,
                modelProfileId, "target-e2e-room-proposal-source.v2", policyVersion, guardrailVersion,
                toolPolicyVersion);
    }
    /** Profile values are deployment expectations; activation-owned bindings must match them exactly. */
    public TargetIntakeRuntimePins requireActivation(
            String activeCaseBuildId, String activeAgentBuildId, String graphKey,
            String graphVersion, String checkpointSchemaVersion, String activeGraphBindingHash,
            String activeGraphCodeBuildId, String activeDomainBindingHash) {
        if (!caseBuildId.equals(activeCaseBuildId)
                || !agentBuildId.equals(activeAgentBuildId)
                || !"all-rooms.target-e2e.v2".equals(graphKey)
                || !"target-e2e-graph.2026-08-18.1".equals(graphVersion)
                || !"target-e2e-checkpoint.v2".equals(checkpointSchemaVersion)
                || !graphBindingHash.equals(activeGraphBindingHash)
                || !graphCodeBuildId.equals(activeGraphCodeBuildId)
                || !isolatedDomainDbBindingHash.equals(activeDomainBindingHash)) {
            throw new IllegalStateException("target Intake profile pins differ from the active activation");
        }
        return this;
    }
}
