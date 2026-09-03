package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import java.util.Objects;

final class IntakeSyntheticRuntimeAuthority {

    private IntakeSyntheticRuntimeAuthority() {}

    static void requireMatches(ActivityAuthority authority, SnapshotPublicationRequest request) {
        requireMatches(
                authority,
                request.envelope(),
                request.threadId(),
                request.agentSessionId(),
                request.operationKey(),
                request.requestHash());
    }

    static void requireMatches(ActivityAuthority authority, GraphExecutionRequest request) {
        requireMatches(
                authority,
                request.envelope(),
                request.threadId(),
                request.agentSessionId(),
                request.operationKey(),
                request.requestHash());
    }

    static void requireMatches(ActivityAuthority authority, TurnFinalizationRequest request) {
        requireMatches(
                authority,
                request.envelope(),
                request.threadId(),
                request.agentSessionId(),
                request.operationKey(),
                request.requestHash());
    }

    static void requireRegistration(
            ActivityEnvelope envelope,
            String threadId,
            String agentSessionId,
            IntakeGraphThreadBinding binding) {
        Objects.requireNonNull(binding, "thread binding must not be null");
        IntakePrivateThreadRegistration registration = binding.registration();
        registration.requireCanonicalHash();
        requireEqual(registration.tenantSurrogate(), envelope.tenantSurrogate(), "tenant");
        requireEqual(registration.caseId(), envelope.caseId(), "case");
        requireEqual(registration.roomEpoch(), envelope.roomEpoch(), "room epoch");
        requireEqual(binding.fencingToken(), envelope.fencingToken(), "fencing token");
        requireEqual(registration.threadId(), threadId, "thread id");
        requireEqual(registration.actorScopeHash(), envelope.actorScopeHash(), "actor scope");
        requireEqual(registration.agentSessionId(), agentSessionId, "agent session");
        requireEqual(
                registration.graphVersion(),
                envelope.pinnedVersions().graphVersion(),
                "graph version");
        requireEqual(
                registration.checkpointSchemaVersion(),
                envelope.pinnedVersions().checkpointSchemaVersion(),
                "checkpoint schema version");
        requireEqual(
                registration.promptVersion(),
                envelope.pinnedVersions().promptVersion(),
                "prompt version");
        requireEqual(
                registration.modelProfileId(),
                envelope.pinnedVersions().modelProfileId(),
                "model profile");
        requireEqual(
                registration.outputSchemaVersion(),
                envelope.pinnedVersions().outputSchemaVersion(),
                "output schema version");
        requireEqual(
                registration.policyVersion(),
                envelope.pinnedVersions().policyVersion(),
                "policy version");
        requireEqual(
                registration.guardrailVersion(),
                envelope.pinnedVersions().guardrailVersion(),
                "guardrail version");
        requireEqual(
                registration.toolPolicyVersion(),
                envelope.pinnedVersions().toolPolicyVersion(),
                "tool policy version");
    }

    static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw new SecurityException("synthetic Intake " + field + " authority mismatch");
        }
    }

    static void requireEqual(long actual, long expected, String field) {
        if (actual != expected) {
            throw new SecurityException("synthetic Intake " + field + " authority mismatch");
        }
    }

    private static void requireMatches(
            ActivityAuthority authority,
            ActivityEnvelope envelope,
            String threadId,
            String agentSessionId,
            String operationKey,
            String requestHash) {
        Objects.requireNonNull(authority, "runtime authority must not be null");
        requireEqual(authority.envelope(), envelope, "Activity envelope");
        requireEqual(authority.threadId(), threadId, "thread id");
        requireEqual(authority.agentSessionId(), agentSessionId, "agent session");
        requireEqual(authority.operationKey(), operationKey, "operation key");
        requireEqual(authority.requestHash(), requestHash, "request hash");
    }
}
