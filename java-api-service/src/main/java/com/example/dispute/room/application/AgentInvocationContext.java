package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.PermissionScope;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record AgentInvocationContext(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("case_id") String caseId,
        @JsonProperty("room_type") RoomType roomType,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("actor_role") String actorRole,
        @JsonProperty("access_session_id") String accessSessionId,
        @JsonProperty("permission_level") String permissionLevel,
        @JsonProperty("permission_scopes") List<String> permissionScopes,
        @JsonProperty("agent_key") String agentKey,
        @JsonProperty("agent_invocation_id") String agentInvocationId,
        @JsonProperty("agent_session_id") String agentSessionId,
        @JsonProperty("conversation_scope") String conversationScope,
        @JsonProperty("scope_type") String scopeType,
        @JsonProperty("allowed_actor_ids") List<String> allowedActorIds,
        @JsonProperty("allowed_actor_roles") List<String> allowedActorRoles,
        @JsonProperty("prompt_profile_id") String promptProfileId,
        @JsonProperty("memory_policy_id") String memoryPolicyId) {

    public AgentInvocationContext {
        permissionScopes = permissionScopes == null ? List.of() : List.copyOf(permissionScopes);
        allowedActorIds = allowedActorIds == null ? List.of() : List.copyOf(allowedActorIds);
        allowedActorRoles = allowedActorRoles == null ? List.of() : List.copyOf(allowedActorRoles);
    }

    public static AgentInvocationContext partyPrivate(
            CaseAccessSessionEntity accessSession,
            AgentConversationSessionEntity agentSession,
            RoomType roomType,
            String scopeType) {
        ActorRole actorRole = accessSession.getActorRole();
        return new AgentInvocationContext(
                accessSession.getTenantId(),
                accessSession.getCaseId(),
                roomType,
                accessSession.getActorId(),
                actorRole.name(),
                accessSession.getId(),
                accessSession.getPermissionLevel().name(),
                accessSession.permissionScopes().stream().map(PermissionScope::name).sorted().toList(),
                agentSession.getAgentKey(),
                "AGENT_INVOCATION_" + compactUuid(),
                agentSession.getId(),
                agentSession.getConversationScope(),
                scopeType,
                List.of(accessSession.getActorId()),
                List.of(actorRole.name()),
                agentSession.getPromptProfileId(),
                agentSession.getMemoryPolicyId());
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
