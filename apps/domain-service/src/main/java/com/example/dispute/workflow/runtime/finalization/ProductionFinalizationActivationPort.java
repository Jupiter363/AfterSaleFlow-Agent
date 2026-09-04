package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter boundary to the Java-verified production-runtime activation authority.
 *
 * <p>The implementation must verify the signed activation manifest and its nonce/replay,
 * environment, database, image, candidate, and revocation authority before returning ALLOWED.
 */
@FunctionalInterface
public interface ProductionFinalizationActivationPort {

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
            long roomFencingToken,
            String authorityActivationId) {

        public AuthorizationRequest(
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
            this(
                    tenantSurrogate,
                    caseId,
                    roomId,
                    roomType,
                    agentRunId,
                    workflowId,
                    workflowRunId,
                    workflowBuildId,
                    commandId,
                    commandHash,
                    commandEnvelopeHash,
                    roomEpoch,
                    roomFencingToken,
                    null);
        }

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
            if (authorityActivationId != null
                    && !authorityActivationId.matches("p9act[.]v1[.][0-9a-f]{32}")) {
                throw new IllegalArgumentException("authorityActivationId is invalid");
            }
        }
    }

    record AuthorizationDecision(
            Decision decision, ActivationGrant grant, RuntimeAttestation runtimeAttestation) {

        public AuthorizationDecision {
            decision = Objects.requireNonNull(decision, "decision");
            boolean allowed = decision == Decision.ALLOWED;
            if (allowed != (grant != null) || allowed != (runtimeAttestation != null)) {
                throw new IllegalArgumentException(
                        "only an allowed activation decision may carry both authority and runtime grants");
            }
        }

        public static AuthorizationDecision allowed(
                ActivationGrant grant, RuntimeAttestation runtimeAttestation) {
            return new AuthorizationDecision(
                    Decision.ALLOWED,
                    Objects.requireNonNull(grant),
                    Objects.requireNonNull(runtimeAttestation));
        }

        public static AuthorizationDecision denied(Decision decision) {
            if (decision == Decision.ALLOWED) {
                throw new IllegalArgumentException("allowed decisions require a grant");
            }
            return new AuthorizationDecision(decision, null, null);
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

    /** Current deployment proof for executing one immutable authority activation's finalizer. */
    record RuntimeAttestation(
            String activationId,
            String authorityActivationId,
            String executionLane,
            String tenantSurrogate,
            Set<RoomType> allowedRoomTypes,
            String expectedAgentBuildId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String activationManifestHash,
            String isolatedDomainDbBindingHash,
            Lifecycle lifecycle,
            Instant issuedAt,
            Instant expiresAt,
            Instant revokedAt) {

        public RuntimeAttestation {
            validatedActivationId(activationId, "activationId");
            validatedActivationId(authorityActivationId, "authorityActivationId");
            required(executionLane, "executionLane");
            required(tenantSurrogate, "tenantSurrogate");
            allowedRoomTypes = Set.copyOf(
                    Objects.requireNonNull(allowedRoomTypes, "allowedRoomTypes"));
            if (allowedRoomTypes.isEmpty()) {
                throw new IllegalArgumentException("runtime room scope must not be empty");
            }
            allowedRoomTypes.forEach(value -> Objects.requireNonNull(value, "allowedRoomType"));
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
                throw new IllegalArgumentException("runtime activation expiry must follow issuance");
            }
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

    private static String validatedActivationId(String value, String field) {
        if (value == null || !value.matches("p9act[.]v1[.][0-9a-f]{32}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
