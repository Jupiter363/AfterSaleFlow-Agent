package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * External authentication and durable admission boundary for the engineering-only synthetic path.
 * Production assembly must supply an ES256 verifier and durably persist the exact admitted tuple
 * before returning it. Test fakes that return {@link VerifiedAdmission} are not
 * signature-verification or persistence evidence. No default implementation is provided.
 */
public interface IntakeSignedSyntheticAdmissionPort {

    VerifiedAdmission admit(AdmissionAttempt attempt, IntakeWorkflowCommand command);

    boolean isActivityAuthorized(ActivityAuthorization authorization);

    record AdmissionAttempt(
            String schemaVersion,
            TrafficSource trafficSource,
            String signingKeyId,
            String compactJws,
            String signedEnvelopeHash,
            String threadId,
            String agentSessionId,
            long deadlineEpochMillis,
            RetryBudget retryBudget) {

        public AdmissionAttempt {
            if (!"intake-signed-synthetic-admission-attempt.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be intake-signed-synthetic-admission-attempt.v1");
            }
            Objects.requireNonNull(trafficSource, "trafficSource must not be null");
            Objects.requireNonNull(retryBudget, "retryBudget must not be null");
        }

        public boolean hasSignatureEvidence() {
            return boundedIdentifier(signingKeyId)
                    && compactJws != null
                    && compactJws.length() <= 16_384
                    && compactJws.matches(
                            "[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                    && sha256(signedEnvelopeHash)
                    && signedEnvelopeHash.equals(sha256Hex(compactJws));
        }
    }

    record VerifiedAdmission(
            String schemaVersion,
            TrafficSource trafficSource,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String commandId,
            long commandSequence,
            IntakeCommandType commandType,
            IntakeParty party,
            String commandPayloadRef,
            String commandPayloadHash,
            String commandOperationKey,
            String actorScopeHash,
            String requestHash,
            String threadId,
            String agentSessionId,
            long deadlineEpochMillis,
            RetryBudget retryBudget,
            String authorizationHash) {

        public VerifiedAdmission {
            if (!"intake-verified-synthetic-admission.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be intake-verified-synthetic-admission.v1");
            }
            Objects.requireNonNull(trafficSource, "trafficSource must not be null");
            Objects.requireNonNull(commandType, "commandType must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(retryBudget, "retryBudget must not be null");
            if (!boundedIdentifier(tenantSurrogate)
                    || !boundedIdentifier(caseId)
                    || !boundedIdentifier(commandId)
                    || !validReference(commandPayloadRef)
                    || !sha256(commandPayloadHash)
                    || !validOperationKey(commandOperationKey, caseId, commandId)
                    || !sha256(actorScopeHash)
                    || !sha256(requestHash)
                    || !validThreadId(threadId)
                    || !boundedIdentifier(agentSessionId)
                    || !sha256(authorizationHash)
                    || roomEpoch < 0
                    || fencingToken < 1
                    || commandSequence < 1
                    || deadlineEpochMillis < 1) {
                throw new IllegalArgumentException("verified synthetic admission is malformed");
            }
        }
    }

    record ActivityAuthorization(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String commandId,
            long commandSequence,
            IntakeCommandType commandType,
            IntakeParty party,
            String commandPayloadRef,
            String commandPayloadHash,
            String commandOperationKey,
            long processRevision,
            long roomRevision,
            String actorScopeHash,
            String requestHash,
            String threadId,
            String agentSessionId,
            long deadlineEpochMillis,
            RetryBudget retryBudget,
            PinnedVersions pinnedVersions) {

        public ActivityAuthorization {
            if (!"intake-synthetic-activity-authorization.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be intake-synthetic-activity-authorization.v1");
            }
            Objects.requireNonNull(commandType, "commandType must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(retryBudget, "retryBudget must not be null");
            Objects.requireNonNull(pinnedVersions, "pinnedVersions must not be null");
            if (deadlineEpochMillis < 1) {
                throw new IllegalArgumentException("deadlineEpochMillis must be positive");
            }
            if (processRevision < 0 || roomRevision < 0) {
                throw new IllegalArgumentException("process/room revision must be non-negative");
            }
        }

        static ActivityAuthorization from(
                ActivityEnvelope envelope,
                String requestHash,
                String threadId,
                String agentSessionId) {
            Objects.requireNonNull(envelope, "envelope must not be null");
            return new ActivityAuthorization(
                    "intake-synthetic-activity-authorization.v1",
                    envelope.tenantSurrogate(),
                    envelope.caseId(),
                    envelope.roomEpoch(),
                    envelope.fencingToken(),
                    envelope.commandId(),
                    envelope.commandSequence(),
                    envelope.commandType(),
                    envelope.party(),
                    envelope.commandPayloadRef(),
                    envelope.commandPayloadHash(),
                    "intake.operation:" + envelope.caseId() + ":" + envelope.commandId(),
                    envelope.processRevision(),
                    envelope.roomRevision(),
                    envelope.actorScopeHash(),
                    requestHash,
                    threadId,
                    agentSessionId,
                    envelope.deadlineEpochMillis(),
                    envelope.retryBudget(),
                    envelope.pinnedVersions());
        }
    }

    private static boolean boundedIdentifier(String value) {
        return value != null
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean validReference(String value) {
        return value != null
                && value.length() <= 1_024
                && value.matches("(?:s3|minio|urn):.+");
    }

    private static boolean validOperationKey(String value, String caseId, String commandId) {
        return value != null
                && value.equals("intake.operation:" + caseId + ":" + commandId)
                && value.length() <= 512;
    }

    private static boolean validThreadId(String value) {
        return value != null && value.matches("grt\\.v1\\.[0-9a-f]{32}");
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
