package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.RoomType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Entity
@Table(name = "agent_conversation_session")
public class AgentConversationSessionEntity extends AbstractEntity {

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false)
    private RoomType roomType;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", length = 64, nullable = false)
    private ActorRole actorRole;

    @Column(name = "agent_key", length = 128, nullable = false)
    private String agentKey;

    @Column(name = "access_session_id", length = 64, nullable = false)
    private String accessSessionId;

    @Column(name = "prompt_profile_id", length = 128, nullable = false)
    private String promptProfileId;

    @Column(name = "memory_policy_id", length = 128, nullable = false)
    private String memoryPolicyId;

    @Column(name = "conversation_scope", length = 512, nullable = false)
    private String conversationScope;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected AgentConversationSessionEntity() {}

    private AgentConversationSessionEntity(String id) {
        super(required(id, "id"));
    }

    public static AgentConversationSessionEntity create(
            String id,
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId,
            String createdBy) {
        AgentConversationSessionEntity entity = new AgentConversationSessionEntity(id);
        entity.tenantId = required(accessSession.getTenantId(), "tenantId");
        entity.caseId = required(accessSession.getCaseId(), "caseId");
        entity.roomType = Objects.requireNonNull(roomType, "roomType must not be null");
        entity.actorId = required(accessSession.getActorId(), "actorId");
        entity.actorRole = Objects.requireNonNull(accessSession.getActorRole());
        entity.agentKey = required(agentKey, "agentKey");
        entity.accessSessionId = required(accessSession.getId(), "accessSessionId");
        entity.promptProfileId = required(promptProfileId, "promptProfileId");
        entity.memoryPolicyId = required(memoryPolicyId, "memoryPolicyId");
        entity.conversationScope =
                conversationScope(
                        entity.tenantId,
                        entity.caseId,
                        entity.roomType,
                        entity.actorId,
                        entity.actorRole,
                        entity.agentKey,
                        entity.promptProfileId,
                        entity.accessSessionId);
        entity.status = "ACTIVE";
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.createdBy = required(createdBy, "createdBy");
        return entity;
    }

    public static String conversationScope(
            String tenantId,
            String caseId,
            RoomType roomType,
            String actorId,
            ActorRole actorRole,
            String agentKey,
            String promptProfileId,
            String accessSessionId) {
        return String.join(
                ":",
                required(tenantId, "tenantId"),
                required(caseId, "caseId"),
                Objects.requireNonNull(roomType, "roomType").name(),
                required(actorId, "actorId"),
                Objects.requireNonNull(actorRole, "actorRole").name(),
                required(agentKey, "agentKey"),
                required(promptProfileId, "promptProfileId"),
                required(accessSessionId, "accessSessionId"));
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCaseId() {
        return caseId;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public String getActorId() {
        return actorId;
    }

    public ActorRole getActorRole() {
        return actorRole;
    }

    public String getAgentKey() {
        return agentKey;
    }

    public String getAccessSessionId() {
        return accessSessionId;
    }

    public String getPromptProfileId() {
        return promptProfileId;
    }

    public String getMemoryPolicyId() {
        return memoryPolicyId;
    }

    public String getConversationScope() {
        return conversationScope;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
