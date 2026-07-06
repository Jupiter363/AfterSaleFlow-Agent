package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.RoomType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "room_turn_memory")
@Immutable
public class RoomTurnMemoryEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false)
    private RoomType roomType;

    @Column(name = "turn_no", nullable = false)
    private int turnNo;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @Column(name = "agent_session_id", length = 64)
    private String agentSessionId;

    @Column(name = "access_session_id", length = 64)
    private String accessSessionId;

    @Column(name = "conversation_scope", length = 512)
    private String conversationScope;

    @Column(name = "session_actor_id", length = 128)
    private String sessionActorId;

    @Column(name = "session_actor_role", length = 64)
    private String sessionActorRole;

    @Column(name = "prompt_profile_id", length = 128)
    private String promptProfileId;

    @Column(name = "answer_role", length = 64)
    private String answerRole;

    @Column(name = "answer_content", columnDefinition = "text")
    private String answerContent;

    @Column(name = "agent_role", length = 128)
    private String agentRole;

    @Column(name = "agent_response", columnDefinition = "text")
    private String agentResponse;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dossier_patch_json", nullable = false, columnDefinition = "jsonb")
    private String dossierPatchJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scroll_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private String scrollSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canvas_operations_json", nullable = false, columnDefinition = "jsonb")
    private String canvasOperationsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "memory_policy_snapshot_json",
            nullable = false,
            columnDefinition = "jsonb")
    private String memoryPolicySnapshotJson;

    @Column(name = "agent_run_id", length = 64)
    private String agentRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected RoomTurnMemoryEntity() {}

    private RoomTurnMemoryEntity(String id) {
        super(required(id, "id"));
    }

    public static RoomTurnMemoryEntity participantTurn(
            String id,
            String caseId,
            RoomType roomType,
            int turnNo,
            String actorId,
            String answerRole,
            String answerContent) {
        RoomTurnMemoryEntity entity = base(id, caseId, roomType, turnNo, actorId);
        entity.answerRole = required(answerRole, "answerRole");
        entity.answerContent = required(answerContent, "answerContent");
        entity.dossierPatchJson = "{}";
        entity.scrollSnapshotJson = "{}";
        entity.canvasOperationsJson = "[]";
        entity.memoryPolicySnapshotJson = "{}";
        return entity;
    }

    public static RoomTurnMemoryEntity participantTurn(
            String id,
            String caseId,
            RoomType roomType,
            int turnNo,
            String actorId,
            String answerRole,
            String answerContent,
            AgentConversationSessionEntity agentSession,
            CaseAccessSessionEntity accessSession,
            String memoryPolicySnapshotJson) {
        RoomTurnMemoryEntity entity =
                participantTurn(id, caseId, roomType, turnNo, actorId, answerRole, answerContent);
        entity.attachSession(agentSession, accessSession, memoryPolicySnapshotJson);
        return entity;
    }

    public static RoomTurnMemoryEntity agentTurn(
            String id,
            String caseId,
            RoomType roomType,
            int turnNo,
            String actorId,
            String agentRole,
            String agentResponse,
            String dossierPatchJson,
            String scrollSnapshotJson,
            String canvasOperationsJson,
            String agentRunId) {
        RoomTurnMemoryEntity entity = base(id, caseId, roomType, turnNo, actorId);
        entity.agentRole = required(agentRole, "agentRole");
        entity.agentResponse = required(agentResponse, "agentResponse");
        entity.dossierPatchJson = required(dossierPatchJson, "dossierPatchJson");
        entity.scrollSnapshotJson = required(scrollSnapshotJson, "scrollSnapshotJson");
        entity.canvasOperationsJson = required(canvasOperationsJson, "canvasOperationsJson");
        entity.memoryPolicySnapshotJson = "{}";
        entity.agentRunId = agentRunId;
        return entity;
    }

    public static RoomTurnMemoryEntity agentTurn(
            String id,
            String caseId,
            RoomType roomType,
            int turnNo,
            String actorId,
            String agentRole,
            String agentResponse,
            String dossierPatchJson,
            String scrollSnapshotJson,
            String canvasOperationsJson,
            String agentRunId,
            AgentConversationSessionEntity agentSession,
            CaseAccessSessionEntity accessSession,
            String memoryPolicySnapshotJson) {
        RoomTurnMemoryEntity entity =
                agentTurn(
                        id,
                        caseId,
                        roomType,
                        turnNo,
                        actorId,
                        agentRole,
                        agentResponse,
                        dossierPatchJson,
                        scrollSnapshotJson,
                        canvasOperationsJson,
                        agentRunId);
        entity.attachSession(agentSession, accessSession, memoryPolicySnapshotJson);
        return entity;
    }

    private void attachSession(
            AgentConversationSessionEntity agentSession,
            CaseAccessSessionEntity accessSession,
            String memoryPolicySnapshotJson) {
        this.agentSessionId = required(agentSession.getId(), "agentSessionId");
        this.accessSessionId = required(accessSession.getId(), "accessSessionId");
        this.conversationScope = required(agentSession.getConversationScope(), "conversationScope");
        this.sessionActorId = required(agentSession.getActorId(), "sessionActorId");
        this.sessionActorRole = required(agentSession.getActorRole().name(), "sessionActorRole");
        this.promptProfileId = required(agentSession.getPromptProfileId(), "promptProfileId");
        this.memoryPolicySnapshotJson =
                memoryPolicySnapshotJson == null || memoryPolicySnapshotJson.isBlank()
                        ? "{}"
                        : memoryPolicySnapshotJson;
    }

    private static RoomTurnMemoryEntity base(
            String id, String caseId, RoomType roomType, int turnNo, String actorId) {
        if (turnNo < 1) {
            throw new IllegalArgumentException("turnNo must be positive");
        }
        RoomTurnMemoryEntity entity = new RoomTurnMemoryEntity(id);
        entity.caseId = required(caseId, "caseId");
        entity.roomType = java.util.Objects.requireNonNull(roomType);
        entity.turnNo = turnNo;
        entity.actorId = required(actorId, "actorId");
        entity.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        entity.createdBy = actorId;
        return entity;
    }

    public String getCaseId() {
        return caseId;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public int getTurnNo() {
        return turnNo;
    }

    public String getActorId() {
        return actorId;
    }

    public String getAgentSessionId() {
        return agentSessionId;
    }

    public String getAccessSessionId() {
        return accessSessionId;
    }

    public String getConversationScope() {
        return conversationScope;
    }

    public String getSessionActorId() {
        return sessionActorId;
    }

    public String getSessionActorRole() {
        return sessionActorRole;
    }

    public String getPromptProfileId() {
        return promptProfileId;
    }

    public String getAnswerRole() {
        return answerRole;
    }

    public String getAnswerContent() {
        return answerContent;
    }

    public String getAgentRole() {
        return agentRole;
    }

    public String getAgentResponse() {
        return agentResponse;
    }

    public String getDossierPatchJson() {
        return dossierPatchJson;
    }

    public String getScrollSnapshotJson() {
        return scrollSnapshotJson;
    }

    public String getCanvasOperationsJson() {
        return canvasOperationsJson;
    }

    public String getMemoryPolicySnapshotJson() {
        return memoryPolicySnapshotJson;
    }

    public String getAgentRunId() {
        return agentRunId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
