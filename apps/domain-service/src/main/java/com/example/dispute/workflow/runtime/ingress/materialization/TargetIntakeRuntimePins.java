package com.example.dispute.workflow.runtime.ingress.materialization;

import com.example.dispute.config.ActorRole;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
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
                TargetTypedRoomProtocol.GRAPH_KEY, TargetTypedRoomProtocol.GRAPH_VERSION,
                TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION, "intake-graph-state.v2", profilePromptVersion,
                modelProfileId, "production-runtime-room-proposal-source.v2", policyVersion, guardrailVersion,
                toolPolicyVersion);
    }
    /** Profile values are deployment expectations; activation-owned bindings must match them exactly. */
    public TargetIntakeRuntimePins requireActivation(
            String activeCaseBuildId, String activeAgentBuildId, String graphKey,
            String graphVersion, String checkpointSchemaVersion, String activeGraphBindingHash,
            String activeGraphCodeBuildId, String activeDomainBindingHash) {
        if (!caseBuildId.equals(activeCaseBuildId)
                || !agentBuildId.equals(activeAgentBuildId)
                || !TargetTypedRoomProtocol.GRAPH_KEY.equals(graphKey)
                || !TargetTypedRoomProtocol.GRAPH_VERSION.equals(graphVersion)
                || !TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION.equals(
                        checkpointSchemaVersion)
                || !graphBindingHash.equals(activeGraphBindingHash)
                || !graphCodeBuildId.equals(activeGraphCodeBuildId)
                || !isolatedDomainDbBindingHash.equals(activeDomainBindingHash)) {
            throw new IllegalStateException("target Intake profile pins differ from the active activation");
        }
        return this;
    }
}
