package com.example.dispute.workflow.shadow.intake.admission;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Exact authority-bearing claims carried by {@code intake-synthetic-admission+jwt}. */
public record IntakeSyntheticAdmissionClaims(
        String schemaVersion,
        String issuer,
        String audience,
        String subject,
        String jwtId,
        long issuedAtEpochSeconds,
        long notBeforeEpochSeconds,
        long expiresAtEpochSeconds,
        String roomType,
        String writerMode,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        long fencingToken,
        String commandId,
        long commandSequence,
        IntakeCommandType commandType,
        IntakeParty party,
        String payloadRef,
        String payloadHash,
        String commandOperationKey,
        long processRevision,
        long roomRevision,
        String actorScopeHash,
        String requestHash,
        String threadId,
        String agentSessionId,
        long deadlineEpochMillis,
        RetryBudget retryBudget,
        String logicalRunId,
        String attemptId,
        String selectionHash,
        String registrationHash,
        Pins pins,
        String parityBaselineRef,
        String parityBaselineHash) {

    public static final String SCHEMA_VERSION = "intake-synthetic-admission-claims.v1";
    public static final String ISSUER = "after-sale-flow.synthetic-driver";
    public static final String AUDIENCE = "after-sale-flow.java-intake-admission";
    public static final String SUBJECT = "signed-synthetic-intake-shadow";

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern THREAD = Pattern.compile("grt\\.v1\\.[0-9a-f]{32}");

    public IntakeSyntheticAdmissionClaims {
        requireEqual(schemaVersion, SCHEMA_VERSION, "schema_version");
        requireEqual(issuer, ISSUER, "iss");
        requireEqual(audience, AUDIENCE, "aud");
        requireEqual(subject, SUBJECT, "sub");
        requireIdentifier(jwtId, "jti");
        requireEqual(roomType, "INTAKE", "room_type");
        requireEqual(writerMode, "SHADOW", "writer_mode");
        requireIdentifier(tenantSurrogate, "tenant_surrogate");
        requireIdentifier(caseId, "case_id");
        requireIdentifier(commandId, "command_id");
        requireReference(payloadRef, "payload_ref");
        requireHash(payloadHash, "payload_hash");
        String expectedOperationKey = "intake.operation:" + caseId + ":" + commandId;
        if (!expectedOperationKey.equals(commandOperationKey) || commandOperationKey.length() > 512) {
            throw malformed("command_operation_key must be the exact root command operation key");
        }
        requireHash(actorScopeHash, "actor_scope_hash");
        requireHash(requestHash, "request_hash");
        if (threadId == null || !THREAD.matcher(threadId).matches()) {
            throw malformed("thread_id must be an opaque graph thread ID");
        }
        requireIdentifier(agentSessionId, "agent_session_id");
        requireIdentifier(logicalRunId, "logical_run_id");
        requireIdentifier(attemptId, "attempt_id");
        requireHash(selectionHash, "selection_hash");
        requireHash(registrationHash, "registration_hash");
        requireReference(parityBaselineRef, "parity_baseline_ref");
        requireHash(parityBaselineHash, "parity_baseline_hash");
        Objects.requireNonNull(commandType, "command_type must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(retryBudget, "retry_budget must not be null");
        Objects.requireNonNull(pins, "pins must not be null");
        if (roomEpoch < 0
                || fencingToken < 1
                || commandSequence < 1
                || processRevision < 0
                || roomRevision < 0
                || deadlineEpochMillis < 1) {
            throw malformed("epoch, fence, sequence, and deadline must be valid");
        }
        if (issuedAtEpochSeconds < 0
                || notBeforeEpochSeconds < issuedAtEpochSeconds
                || expiresAtEpochSeconds <= notBeforeEpochSeconds
                || expiresAtEpochSeconds - issuedAtEpochSeconds > 60) {
            throw malformed("iat/nbf/exp must describe a validity window of at most 60 seconds");
        }
    }

    public boolean isValidAt(Instant instant) {
        long now = Objects.requireNonNull(instant, "instant").getEpochSecond();
        return issuedAtEpochSeconds <= now
                && notBeforeEpochSeconds <= now
                && expiresAtEpochSeconds > now;
    }

    public PinnedVersions activityPins() {
        return new PinnedVersions(
                "intake-pinned-versions.v1",
                pins.roomWorkflowBuildId(),
                pins.graphVersion(),
                pins.checkpointSchemaVersion(),
                pins.promptVersion(),
                pins.modelProfileId(),
                pins.outputSchemaVersion(),
                pins.policyVersion(),
                pins.guardrailVersion(),
                pins.toolPolicyVersion());
    }

    public record Pins(
            String caseWorkflowType,
            String caseWorkflowBuildId,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            String processContractVersion,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String stateSchemaVersion,
            String streamProtocol,
            String promptVersion,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion,
            String cohortPolicyVersion,
            String agentKey,
            String agentSessionProfileVersion,
            String memoryPolicyId) {

        public Pins {
            requireIdentifier(caseWorkflowType, "case_workflow_type");
            requireIdentifier(caseWorkflowBuildId, "case_workflow_build_id");
            requireEqual(roomWorkflowType, "IntakeRoomWorkflow", "room_workflow_type");
            requireIdentifier(roomWorkflowBuildId, "room_workflow_build_id");
            requireIdentifier(processContractVersion, "process_contract_version");
            requireEqual(graphKey, "intake.v2", "graph_key");
            requireIdentifier(graphVersion, "graph_version");
            requireIdentifier(checkpointSchemaVersion, "checkpoint_schema_version");
            requireEqual(stateSchemaVersion, "intake-graph-state.v2", "state_schema_version");
            requireIdentifier(streamProtocol, "stream_protocol");
            requireIdentifier(promptVersion, "prompt_version");
            requireIdentifier(modelProfileId, "model_profile_id");
            requireEqual(outputSchemaVersion, "intake-turn-proposal.v2", "output_schema_version");
            requireIdentifier(policyVersion, "policy_version");
            requireIdentifier(guardrailVersion, "guardrail_version");
            requireEqual(toolPolicyVersion, "no-tools.v1", "tool_policy_version");
            requireIdentifier(cohortPolicyVersion, "cohort_policy_version");
            requireEqual(agentKey, "DISPUTE_INTAKE_OFFICER", "agent_key");
            requireEqual(
                    agentSessionProfileVersion,
                    "agent-session-profile.v1",
                    "agent_session_profile_version");
            requireEqual(
                    memoryPolicyId,
                    "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1",
                    "memory_policy_id");
        }
    }

    private static void requireEqual(String actual, String expected, String field) {
        if (!expected.equals(actual)) {
            throw malformed(field + " must be " + expected);
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw malformed(field + " must be a bounded identifier");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw malformed(field + " must be lowercase SHA-256");
        }
    }

    private static void requireReference(String value, String field) {
        if (value == null || value.length() > 1_024) {
            throw malformed(field + " must be a bounded immutable reference");
        }
        try {
            String scheme = URI.create(value).getScheme();
            if (!("s3".equals(scheme) || "minio".equals(scheme) || "urn".equals(scheme))) {
                throw malformed(field + " must use s3, minio, or urn");
            }
        } catch (IllegalArgumentException exception) {
            throw malformed(field + " must be a valid immutable reference");
        }
    }

    private static IntakeSyntheticAdmissionException malformed(String message) {
        return new IntakeSyntheticAdmissionException("ADMISSION_CLAIMS_INVALID", message);
    }
}
