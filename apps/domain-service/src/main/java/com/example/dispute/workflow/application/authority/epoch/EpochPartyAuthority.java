package com.example.dispute.workflow.application.authority.epoch;

import com.example.dispute.config.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Immutable route authority for one case party in one epoch. */
public record EpochPartyAuthority(
        String authorityId,
        String epochId,
        Party party,
        String tenantSurrogate,
        String caseId,
        String sessionTenantId,
        String sessionCaseId,
        RoomType roomType,
        long roomEpoch,
        long fencingToken,
        String registrationId,
        String registrationHash,
        String threadId,
        String actorId,
        ActorRole actorRole,
        ActorRole audience,
        String actorScopeHash,
        String accessSessionId,
        String permissionLevel,
        String agentSessionId,
        String agentKey,
        String promptVersion,
        String agentSessionProfileVersion,
        String promptProfileId,
        String memoryPolicyId,
        OffsetDateTime createdAt) {

    public enum Party { INITIATOR, RESPONDENT }

    public EpochPartyAuthority {
        required(authorityId, "authorityId");
        required(epochId, "epochId");
        Objects.requireNonNull(party, "party must not be null");
        required(tenantSurrogate, "tenantSurrogate");
        required(caseId, "caseId");
        if (!tenantSurrogate.equals(sessionTenantId) || !caseId.equals(sessionCaseId)) {
            throw new IllegalArgumentException("session scope must equal epoch scope");
        }
        Objects.requireNonNull(roomType, "roomType must not be null");
        if (roomType != RoomType.INTAKE) {
            throw new IllegalArgumentException("party authority roomType must be INTAKE");
        }
        if (roomEpoch < 0 || fencingToken <= 0) {
            throw new IllegalArgumentException("roomEpoch must be non-negative and fencingToken positive");
        }
        required(registrationId, "registrationId");
        requireSha256(registrationHash, "registrationHash");
        required(threadId, "threadId");
        required(actorId, "actorId");
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        Objects.requireNonNull(audience, "audience must not be null");
        if (actorRole != ActorRole.USER && actorRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("party actorRole must be USER or MERCHANT");
        }
        if (actorRole != audience) {
            throw new IllegalArgumentException("actor role must equal audience");
        }
        requireSha256(actorScopeHash, "actorScopeHash");
        required(accessSessionId, "accessSessionId");
        String expectedPermission =
                actorRole == ActorRole.USER ? "PARTY_USER" : "PARTY_MERCHANT";
        if (!expectedPermission.equals(permissionLevel)) {
            throw new IllegalArgumentException("party permission does not match actor role");
        }
        required(agentSessionId, "agentSessionId");
        if (!"DISPUTE_INTAKE_OFFICER".equals(agentKey)) {
            throw new IllegalArgumentException("agentKey must be DISPUTE_INTAKE_OFFICER");
        }
        required(promptVersion, "promptVersion");
        if (!"agent-session-profile.v1".equals(agentSessionProfileVersion)) {
            throw new IllegalArgumentException(
                    "agentSessionProfileVersion must be agent-session-profile.v1");
        }
        AgentSessionProfileRegistry.requireExact(
                promptProfileId,
                agentKey,
                actorRole,
                promptVersion,
                agentSessionProfileVersion);
        if (!"GRAPH_PRIVATE_NO_MEMORY_FRAME_V1".equals(memoryPolicyId)) {
            throw new IllegalArgumentException(
                    "memoryPolicyId must be GRAPH_PRIVATE_NO_MEMORY_FRAME_V1");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }
}
