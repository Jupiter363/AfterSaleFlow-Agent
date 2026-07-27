package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter boundary to the Java-verified target-E2E activation authority.
 *
 * <p>The implementation must verify the signed activation manifest and its nonce/replay,
 * environment, database, image, candidate, and revocation authority before returning ALLOWED.
 */
@FunctionalInterface
public interface TargetE2eFinalizationActivationPort {

    long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    AuthorizationDecision authorize(AuthorizationRequest request);

    record AuthorizationRequest(
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            String agentRunId,
            String workflowId,
            String workflowRunId,
            String workflowBuildId,
            String commandId,
            String commandHash,
            String commandEnvelopeHash,
            long roomEpoch,
            long roomFencingToken) {

        public AuthorizationRequest {
            required(tenantSurrogate, "tenantSurrogate");
            required(caseId, "caseId");
            required(roomId, "roomId");
            roomType = Objects.requireNonNull(roomType, "roomType");
            required(agentRunId, "agentRunId");
            required(workflowId, "workflowId");
            required(workflowRunId, "workflowRunId");
            required(workflowBuildId, "workflowBuildId");
            required(commandId, "commandId");
            sha256(commandHash, "commandHash");
            sha256(commandEnvelopeHash, "commandEnvelopeHash");
            if (roomEpoch < 0
                    || roomEpoch > MAX_SAFE_INTEGER
                    || roomFencingToken < 1
                    || roomFencingToken > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("room epoch or fencing token is invalid");
            }
        }
    }

    record AuthorizationDecision(Decision decision, ActivationGrant grant) {

        public AuthorizationDecision {
            decision = Objects.requireNonNull(decision, "decision");
            if ((decision == Decision.ALLOWED) != (grant != null)) {
                throw new IllegalArgumentException(
                        "only an allowed activation decision may carry a grant");
            }
        }

        public static AuthorizationDecision allowed(ActivationGrant grant) {
            return new AuthorizationDecision(Decision.ALLOWED, Objects.requireNonNull(grant));
        }

        public static AuthorizationDecision denied(Decision decision) {
            if (decision == Decision.ALLOWED) {
                throw new IllegalArgumentException("allowed decisions require a grant");
            }
            return new AuthorizationDecision(decision, null);
        }
    }

    record ActivationGrant(
            String activationId,
            String executionLane,
            String tenantSurrogate,
            Set<String> allowedCaseIds,
            Set<RoomType> allowedRoomTypes,
            String expectedAgentBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String activationManifestHash,
            String isolatedDomainDbBindingHash,
            Lifecycle lifecycle,
            AcceptedCommandProof acceptedCommandProof,
            Instant issuedAt,
            Instant expiresAt,
            Instant revokedAt) {

        public ActivationGrant {
            required(activationId, "activationId");
            required(executionLane, "executionLane");
            required(tenantSurrogate, "tenantSurrogate");
            allowedCaseIds = Set.copyOf(Objects.requireNonNull(allowedCaseIds, "allowedCaseIds"));
            allowedRoomTypes = Set.copyOf(
                    Objects.requireNonNull(allowedRoomTypes, "allowedRoomTypes"));
            if (allowedCaseIds.isEmpty() || allowedRoomTypes.isEmpty()) {
                throw new IllegalArgumentException("activation scope must not be empty");
            }
            allowedCaseIds.forEach(value -> required(value, "allowedCaseId"));
            required(expectedAgentBuildId, "expectedAgentBuildId");
            required(graphKey, "graphKey");
            required(graphVersion, "graphVersion");
            required(checkpointSchemaVersion, "checkpointSchemaVersion");
            sha256(activationManifestHash, "activationManifestHash");
            sha256(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("activation expiry must follow issuance");
            }
            if ((lifecycle == Lifecycle.DRAIN_ONLY) != (acceptedCommandProof != null)) {
                throw new IllegalArgumentException(
                        "DRAIN_ONLY grants require one accepted command proof");
            }
        }
    }

    record AcceptedCommandProof(
            String commandId,
            String commandHash,
            String commandEnvelopeHash,
            long roomEpoch,
            long roomFencingToken,
            Instant admittedAt) {
        public AcceptedCommandProof {
            required(commandId, "commandId");
            sha256(commandHash, "commandHash");
            sha256(commandEnvelopeHash, "commandEnvelopeHash");
            if (roomEpoch < 0
                    || roomEpoch > MAX_SAFE_INTEGER
                    || roomFencingToken < 1
                    || roomFencingToken > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("accepted command fence is invalid");
            }
            admittedAt = Objects.requireNonNull(admittedAt, "admittedAt");
        }
    }

    enum Lifecycle {
        ACTIVE,
        DRAIN_ONLY,
        DRAINED,
        REVOKED_TERMINAL
    }

    enum Decision {
        ALLOWED,
        ABSENT,
        SIGNATURE_INVALID,
        SCOPE_DENIED,
        EXPIRED,
        REVOKED,
        NONCE_REPLAYED,
        ENVIRONMENT_MISMATCH
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }
}
