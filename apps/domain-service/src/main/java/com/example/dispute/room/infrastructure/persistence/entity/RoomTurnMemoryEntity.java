/*
 * 所属模块：房间协作与权限。
 * 文件职责：映射房间轮次记忆数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「participantTurn」、「agentTurn」、「getCaseId」、「getRoomType」、「getTurnNo」、「getActorId」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
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

// 所属模块：【房间协作与权限 / JPA 实体层】类型「RoomTurnMemoryEntity」。
// 类型职责：映射房间轮次记忆数据库记录并保存可审计状态；本类型显式提供 「RoomTurnMemoryEntity」、「RoomTurnMemoryEntity」、「participantTurn」、「participantTurn」、「agentTurn」、「agentTurn」。
// 协作关系：主要由 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.persistAgentTurn」、「EvidenceAgentTurnService.persistOpeningAgentTurn」、「IntakeAgentTurnService.continueFromParticipantMessage」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.RoomTurnMemoryEntity()」。
    // 具体功能：「RoomTurnMemoryEntity.RoomTurnMemoryEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「RoomTurnMemoryEntity.RoomTurnMemoryEntity()」的上游创建点包括 「RoomTurnMemoryEntity.base」。
    // 下游影响：「RoomTurnMemoryEntity.RoomTurnMemoryEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RoomTurnMemoryEntity.RoomTurnMemoryEntity()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected RoomTurnMemoryEntity() {}

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.RoomTurnMemoryEntity(String)」。
    // 具体功能：「RoomTurnMemoryEntity.RoomTurnMemoryEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「RoomTurnMemoryEntity.RoomTurnMemoryEntity(String)」的上游创建点包括 「RoomTurnMemoryEntity.base」。
    // 下游影响：「RoomTurnMemoryEntity.RoomTurnMemoryEntity(String)」向下依次触达 「required」。
    // 系统意义：「RoomTurnMemoryEntity.RoomTurnMemoryEntity(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private RoomTurnMemoryEntity(String id) {
        super(required(id, "id"));
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String)」。
    // 具体功能：「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String)」：更新参与人轮次：先更新内部状态 「answerRole」、「answerContent」、「dossierPatchJson」、「scrollSnapshotJson」；实际协作者为 「base」、「required」；处理的关键状态/协议值包括 「answerRole」、「answerContent」、「{}」、「[]」，最终返回「RoomTurnMemoryEntity」。
    // 上游调用：「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.continueFromParticipantMessage」、「RoomTurnMemoryEntity.participantTurn」、「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply」。
    // 下游影响：「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String)」向下依次触达 「base」、「required」；计算结果以「RoomTurnMemoryEntity」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」。
    // 具体功能：「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」：提供「participantTurn」的便捷重载：接收 「id」(String)、「caseId」(String)、「roomType」(RoomType)、「turnNo」(int)、「actorId」(String)、「answerRole」(String)、「answerContent」(String)、「agentSession」(AgentConversationSessionEntity)、「accessSession」(CaseAccessSessionEntity)、「memoryPolicySnapshotJson」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.continueFromParticipantMessage」、「RoomTurnMemoryEntity.participantTurn」、「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply」。
    // 下游影响：「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」向下依次触达 「entity.attachSession」、「participantTurn」；计算结果以「RoomTurnMemoryEntity」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.participantTurn(String,String,RoomType,int,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String)」。
    // 具体功能：「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String)」：更新Agent轮次：先更新内部状态 「agentRole」、「agentResponse」、「dossierPatchJson」、「scrollSnapshotJson」；实际协作者为 「base」、「required」；处理的关键状态/协议值包括 「agentRole」、「agentResponse」、「dossierPatchJson」、「scrollSnapshotJson」，最终返回「RoomTurnMemoryEntity」。
    // 上游调用：「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String)」的上游调用点包括 「EvidenceAgentTurnService.persistOpeningAgentTurn」、「EvidenceAgentTurnService.persistAgentTurn」、「IntakeAgentTurnService.persistAgentTurn」、「RoomTurnMemoryEntity.agentTurn」。
    // 下游影响：「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String)」向下依次触达 「base」、「required」；计算结果以「RoomTurnMemoryEntity」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」。
    // 具体功能：「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」：提供「agentTurn」的便捷重载：接收 「id」(String)、「caseId」(String)、「roomType」(RoomType)、「turnNo」(int)、「actorId」(String)、「agentRole」(String)、「agentResponse」(String)、「dossierPatchJson」(String)、「scrollSnapshotJson」(String)、「canvasOperationsJson」(String)、「agentRunId」(String)、「agentSession」(AgentConversationSessionEntity)、「accessSession」(CaseAccessSessionEntity)、「memoryPolicySnapshotJson」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」的上游调用点包括 「EvidenceAgentTurnService.persistOpeningAgentTurn」、「EvidenceAgentTurnService.persistAgentTurn」、「IntakeAgentTurnService.persistAgentTurn」、「RoomTurnMemoryEntity.agentTurn」。
    // 下游影响：「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」向下依次触达 「entity.attachSession」、「agentTurn」；计算结果以「RoomTurnMemoryEntity」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.agentTurn(String,String,RoomType,int,String,String,String,String,String,String,String,AgentConversationSessionEntity,CaseAccessSessionEntity,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.attachSession(AgentConversationSessionEntity,CaseAccessSessionEntity,String)」。
    // 具体功能：「RoomTurnMemoryEntity.attachSession(AgentConversationSessionEntity,CaseAccessSessionEntity,String)」：按attach会话：先更新内部状态 「agentSessionId」、「accessSessionId」、「conversationScope」、「sessionActorId」；实际协作者为 「agentSession.getId」、「accessSession.getId」、「agentSession.getConversationScope」、「agentSession.getActorId」；处理的关键状态/协议值包括 「agentSessionId」、「accessSessionId」、「conversationScope」、「sessionActorId」，最终返回「void」。
    // 上游调用：「RoomTurnMemoryEntity.attachSession(AgentConversationSessionEntity,CaseAccessSessionEntity,String)」只由「RoomTurnMemoryEntity」内部流程使用，负责封装“attach会话”这一步校验、映射或状态转换。
    // 下游影响：「RoomTurnMemoryEntity.attachSession(AgentConversationSessionEntity,CaseAccessSessionEntity,String)」向下依次触达 「agentSession.getId」、「accessSession.getId」、「agentSession.getConversationScope」、「agentSession.getActorId」。
    // 系统意义：「RoomTurnMemoryEntity.attachSession(AgentConversationSessionEntity,CaseAccessSessionEntity,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.base(String,String,RoomType,int,String)」。
    // 具体功能：「RoomTurnMemoryEntity.base(String,String,RoomType,int,String)」：更新base：先更新内部状态 「caseId」、「roomType」、「turnNo」、「actorId」；实际协作者为 「required」、「java.util.Objects.requireNonNull」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「caseId」、「actorId」，最终返回「RoomTurnMemoryEntity」。
    // 上游调用：「RoomTurnMemoryEntity.base(String,String,RoomType,int,String)」的上游调用点包括 「RoomTurnMemoryEntity.participantTurn」、「RoomTurnMemoryEntity.agentTurn」。
    // 下游影响：「RoomTurnMemoryEntity.base(String,String,RoomType,int,String)」向下依次触达 「required」、「java.util.Objects.requireNonNull」；计算结果以「RoomTurnMemoryEntity」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.base(String,String,RoomType,int,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getCaseId()」。
    // 具体功能：「RoomTurnMemoryEntity.getCaseId()」：读取「RoomTurnMemoryEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getCaseId()」的上游调用点包括 「RoomTurnMemoryQueryService.view」。
    // 下游影响：「RoomTurnMemoryEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getCaseId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getRoomType()」。
    // 具体功能：「RoomTurnMemoryEntity.getRoomType()」：读取「RoomTurnMemoryEntity」中的「roomType」状态，向 JPA、应用服务或序列化层返回「RoomType」。
    // 上游调用：「RoomTurnMemoryEntity.getRoomType()」的上游调用点包括 「RoomTurnMemoryQueryService.view」。
    // 下游影响：「RoomTurnMemoryEntity.getRoomType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RoomType」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getRoomType()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public RoomType getRoomType() {
        return roomType;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getTurnNo()」。
    // 具体功能：「RoomTurnMemoryEntity.getTurnNo()」：读取「RoomTurnMemoryEntity」中的「turnNo」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「RoomTurnMemoryEntity.getTurnNo()」的上游调用点包括 「IntakeAgentTurnService.toParticipantMessage」、「RoomTurnMemoryQueryService.view」。
    // 下游影响：「RoomTurnMemoryEntity.getTurnNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getTurnNo()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public int getTurnNo() {
        return turnNo;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getActorId()」。
    // 具体功能：「RoomTurnMemoryEntity.getActorId()」：读取「RoomTurnMemoryEntity」中的「actorId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getActorId()」由使用「RoomTurnMemoryEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomTurnMemoryEntity.getActorId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getActorId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getActorId() {
        return actorId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getAgentSessionId()」。
    // 具体功能：「RoomTurnMemoryEntity.getAgentSessionId()」：读取「RoomTurnMemoryEntity」中的「agentSessionId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getAgentSessionId()」由使用「RoomTurnMemoryEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomTurnMemoryEntity.getAgentSessionId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getAgentSessionId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAgentSessionId() {
        return agentSessionId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getAccessSessionId()」。
    // 具体功能：「RoomTurnMemoryEntity.getAccessSessionId()」：读取「RoomTurnMemoryEntity」中的「accessSessionId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getAccessSessionId()」由使用「RoomTurnMemoryEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomTurnMemoryEntity.getAccessSessionId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getAccessSessionId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAccessSessionId() {
        return accessSessionId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getConversationScope()」。
    // 具体功能：「RoomTurnMemoryEntity.getConversationScope()」：读取「RoomTurnMemoryEntity」中的「conversationScope」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getConversationScope()」由使用「RoomTurnMemoryEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomTurnMemoryEntity.getConversationScope()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getConversationScope()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getConversationScope() {
        return conversationScope;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getSessionActorId()」。
    // 具体功能：「RoomTurnMemoryEntity.getSessionActorId()」：读取「RoomTurnMemoryEntity」中的「sessionActorId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getSessionActorId()」由使用「RoomTurnMemoryEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomTurnMemoryEntity.getSessionActorId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getSessionActorId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getSessionActorId() {
        return sessionActorId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getSessionActorRole()」。
    // 具体功能：「RoomTurnMemoryEntity.getSessionActorRole()」：读取「RoomTurnMemoryEntity」中的「sessionActorRole」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getSessionActorRole()」由使用「RoomTurnMemoryEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomTurnMemoryEntity.getSessionActorRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getSessionActorRole()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getSessionActorRole() {
        return sessionActorRole;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getPromptProfileId()」。
    // 具体功能：「RoomTurnMemoryEntity.getPromptProfileId()」：读取「RoomTurnMemoryEntity」中的「promptProfileId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getPromptProfileId()」由使用「RoomTurnMemoryEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomTurnMemoryEntity.getPromptProfileId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getPromptProfileId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getPromptProfileId() {
        return promptProfileId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getAnswerRole()」。
    // 具体功能：「RoomTurnMemoryEntity.getAnswerRole()」：读取「RoomTurnMemoryEntity」中的「answerRole」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getAnswerRole()」的上游调用点包括 「IntakeAgentTurnService.toParticipantMessage」。
    // 下游影响：「RoomTurnMemoryEntity.getAnswerRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getAnswerRole()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAnswerRole() {
        return answerRole;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getAnswerContent()」。
    // 具体功能：「RoomTurnMemoryEntity.getAnswerContent()」：读取「RoomTurnMemoryEntity」中的「answerContent」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getAnswerContent()」的上游调用点包括 「IntakeAgentTurnService.toParticipantMessage」。
    // 下游影响：「RoomTurnMemoryEntity.getAnswerContent()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getAnswerContent()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAnswerContent() {
        return answerContent;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getAgentRole()」。
    // 具体功能：「RoomTurnMemoryEntity.getAgentRole()」：读取「RoomTurnMemoryEntity」中的「agentRole」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getAgentRole()」的上游调用点包括 「RoomTurnMemoryQueryService.view」。
    // 下游影响：「RoomTurnMemoryEntity.getAgentRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getAgentRole()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAgentRole() {
        return agentRole;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getAgentResponse()」。
    // 具体功能：「RoomTurnMemoryEntity.getAgentResponse()」：读取「RoomTurnMemoryEntity」中的「agentResponse」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getAgentResponse()」的上游调用点包括 「RoomTurnMemoryQueryService.view」。
    // 下游影响：「RoomTurnMemoryEntity.getAgentResponse()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getAgentResponse()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAgentResponse() {
        return agentResponse;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getDossierPatchJson()」。
    // 具体功能：「RoomTurnMemoryEntity.getDossierPatchJson()」：读取「RoomTurnMemoryEntity」中的「dossierPatchJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getDossierPatchJson()」的上游调用点包括 「RoomTurnMemoryQueryService.view」。
    // 下游影响：「RoomTurnMemoryEntity.getDossierPatchJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getDossierPatchJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getDossierPatchJson() {
        return dossierPatchJson;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getScrollSnapshotJson()」。
    // 具体功能：「RoomTurnMemoryEntity.getScrollSnapshotJson()」：读取「RoomTurnMemoryEntity」中的「scrollSnapshotJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getScrollSnapshotJson()」的上游调用点包括 「RoomTurnMemoryQueryService.view」。
    // 下游影响：「RoomTurnMemoryEntity.getScrollSnapshotJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getScrollSnapshotJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getScrollSnapshotJson() {
        return scrollSnapshotJson;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getCanvasOperationsJson()」。
    // 具体功能：「RoomTurnMemoryEntity.getCanvasOperationsJson()」：读取「RoomTurnMemoryEntity」中的「canvasOperationsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getCanvasOperationsJson()」的上游调用点包括 「RoomTurnMemoryQueryService.view」。
    // 下游影响：「RoomTurnMemoryEntity.getCanvasOperationsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getCanvasOperationsJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getCanvasOperationsJson() {
        return canvasOperationsJson;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getMemoryPolicySnapshotJson()」。
    // 具体功能：「RoomTurnMemoryEntity.getMemoryPolicySnapshotJson()」：读取「RoomTurnMemoryEntity」中的「memoryPolicySnapshotJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getMemoryPolicySnapshotJson()」由使用「RoomTurnMemoryEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomTurnMemoryEntity.getMemoryPolicySnapshotJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getMemoryPolicySnapshotJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getMemoryPolicySnapshotJson() {
        return memoryPolicySnapshotJson;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getAgentRunId()」。
    // 具体功能：「RoomTurnMemoryEntity.getAgentRunId()」：读取「RoomTurnMemoryEntity」中的「agentRunId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.getAgentRunId()」由使用「RoomTurnMemoryEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomTurnMemoryEntity.getAgentRunId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getAgentRunId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAgentRunId() {
        return agentRunId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.getCreatedAt()」。
    // 具体功能：「RoomTurnMemoryEntity.getCreatedAt()」：读取「RoomTurnMemoryEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「RoomTurnMemoryEntity.getCreatedAt()」的上游调用点包括 「RoomTurnMemoryQueryService.view」。
    // 下游影响：「RoomTurnMemoryEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomTurnMemoryEntity.required(String,String)」。
    // 具体功能：「RoomTurnMemoryEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「RoomTurnMemoryEntity.required(String,String)」的上游调用点包括 「RoomTurnMemoryEntity.RoomTurnMemoryEntity」、「RoomTurnMemoryEntity.participantTurn」、「RoomTurnMemoryEntity.agentTurn」、「RoomTurnMemoryEntity.attachSession」。
    // 下游影响：「RoomTurnMemoryEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomTurnMemoryEntity.required(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
