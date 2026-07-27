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

    AuthorizationDecision authorize(AuthorizationRequest request);

    record AuthorizationRequest(
            String tenantSurrogate,
            String caseId,
            String roomId,
            RoomType roomType,
            String agentRunId,
            String workflowId,
            String workflowRunId,
            String workflowBuildId) {

        public AuthorizationRequest {
            required(tenantSurrogate, "tenantSurrogate");
            required(caseId, "caseId");
            required(roomId, "roomId");
            roomType = Objects.requireNonNull(roomType, "roomType");
            required(agentRunId, "agentRunId");
            required(workflowId, "workflowId");
            required(workflowRunId, "workflowRunId");
            required(workflowBuildId, "workflowBuildId");
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
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("activation expiry must follow issuance");
            }
        }
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
}
