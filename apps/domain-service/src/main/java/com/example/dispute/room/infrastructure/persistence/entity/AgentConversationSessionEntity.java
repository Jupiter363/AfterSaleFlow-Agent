/*
 * 所属模块：房间协作与权限。
 * 文件职责：映射Agent会话会话数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「conversationScope」、「getTenantId」、「getCaseId」、「getRoomType」、「getActorId」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
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

// 所属模块：【房间协作与权限 / JPA 实体层】类型「AgentConversationSessionEntity」。
// 类型职责：映射Agent会话会话数据库记录并保存可审计状态；本类型显式提供 「AgentConversationSessionEntity」、「AgentConversationSessionEntity」、「create」、「conversationScope」、「getTenantId」、「getCaseId」。
// 协作关系：主要由 「AgentInvocationContext.partyPrivate」、「AgentSessionInitializer.initialize」、「EvidenceAgentTurnService.appendAgentMessage」、「EvidenceContextEnvelopeFactory.actorSnapshot」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.AgentConversationSessionEntity()」。
    // 具体功能：「AgentConversationSessionEntity.AgentConversationSessionEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AgentConversationSessionEntity.AgentConversationSessionEntity()」的上游创建点包括 「AgentConversationSessionEntity.create」。
    // 下游影响：「AgentConversationSessionEntity.AgentConversationSessionEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentConversationSessionEntity.AgentConversationSessionEntity()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected AgentConversationSessionEntity() {}

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.AgentConversationSessionEntity(String)」。
    // 具体功能：「AgentConversationSessionEntity.AgentConversationSessionEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AgentConversationSessionEntity.AgentConversationSessionEntity(String)」的上游创建点包括 「AgentConversationSessionEntity.create」。
    // 下游影响：「AgentConversationSessionEntity.AgentConversationSessionEntity(String)」向下依次触达 「required」。
    // 系统意义：「AgentConversationSessionEntity.AgentConversationSessionEntity(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private AgentConversationSessionEntity(String id) {
        super(required(id, "id"));
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.create(String,CaseAccessSessionEntity,RoomType,String,String,String,String)」。
    // 具体功能：「AgentConversationSessionEntity.create(String,CaseAccessSessionEntity,RoomType,String,String,String,String)」：创建Agent会话会话：先更新内部状态 「tenantId」、「caseId」、「roomType」、「actorId」；实际协作者为 「Objects.requireNonNull」、「accessSession.getTenantId」、「accessSession.getCaseId」、「accessSession.getActorId」；处理的关键状态/协议值包括 「tenantId」、「caseId」、「actorId」、「agentKey」，最终返回「AgentConversationSessionEntity」。
    // 上游调用：「AgentConversationSessionEntity.create(String,CaseAccessSessionEntity,RoomType,String,String,String,String)」的上游调用点包括 「AgentSessionInitializer.initialize」、「AgentConversationSessionResolverTest.resolvesSameActorRoomAgentAndProfileToExistingSession」、「AgentConversationSessionResolverTest.createsSessionWithDeterministicScopeAndAccessSessionLink」、「AgentConversationSessionResolverTest.differentAgentKeysDoNotShareSession」。
    // 下游影响：「AgentConversationSessionEntity.create(String,CaseAccessSessionEntity,RoomType,String,String,String,String)」向下依次触达 「Objects.requireNonNull」、「accessSession.getTenantId」、「accessSession.getCaseId」、「accessSession.getActorId」；计算结果以「AgentConversationSessionEntity」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.create(String,CaseAccessSessionEntity,RoomType,String,String,String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.conversationScope(String,String,RoomType,String,ActorRole,String,String,String)」。
    // 具体功能：「AgentConversationSessionEntity.conversationScope(String,String,RoomType,String,ActorRole,String,String,String)」：构建会话作用域；实际协作者为 「String.join」、「Objects.requireNonNull」、「required」；处理的关键状态/协议值包括 「:」、「tenantId」、「caseId」、「roomType」，最终返回「String」。
    // 上游调用：「AgentConversationSessionEntity.conversationScope(String,String,RoomType,String,ActorRole,String,String,String)」的上游调用点包括 「AgentConversationSessionEntity.create」。
    // 下游影响：「AgentConversationSessionEntity.conversationScope(String,String,RoomType,String,ActorRole,String,String,String)」向下依次触达 「String.join」、「Objects.requireNonNull」、「required」；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.conversationScope(String,String,RoomType,String,ActorRole,String,String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getTenantId()」。
    // 具体功能：「AgentConversationSessionEntity.getTenantId()」：读取「AgentConversationSessionEntity」中的「tenantId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentConversationSessionEntity.getTenantId()」由使用「AgentConversationSessionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentConversationSessionEntity.getTenantId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getTenantId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getTenantId() {
        return tenantId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getCaseId()」。
    // 具体功能：「AgentConversationSessionEntity.getCaseId()」：读取「AgentConversationSessionEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentConversationSessionEntity.getCaseId()」由使用「AgentConversationSessionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentConversationSessionEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getCaseId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getRoomType()」。
    // 具体功能：「AgentConversationSessionEntity.getRoomType()」：读取「AgentConversationSessionEntity」中的「roomType」状态，向 JPA、应用服务或序列化层返回「RoomType」。
    // 上游调用：「AgentConversationSessionEntity.getRoomType()」由使用「AgentConversationSessionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentConversationSessionEntity.getRoomType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RoomType」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getRoomType()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public RoomType getRoomType() {
        return roomType;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getActorId()」。
    // 具体功能：「AgentConversationSessionEntity.getActorId()」：读取「AgentConversationSessionEntity」中的「actorId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentConversationSessionEntity.getActorId()」的上游调用点包括 「EvidenceAgentTurnService.appendAgentMessage」、「IntakeAgentTurnService.appendAgentMessage」、「RoomTurnMemoryEntity.attachSession」。
    // 下游影响：「AgentConversationSessionEntity.getActorId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getActorId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getActorId() {
        return actorId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getActorRole()」。
    // 具体功能：「AgentConversationSessionEntity.getActorRole()」：读取「AgentConversationSessionEntity」中的「actorRole」状态，向 JPA、应用服务或序列化层返回「ActorRole」。
    // 上游调用：「AgentConversationSessionEntity.getActorRole()」的上游调用点包括 「RoomTurnMemoryEntity.attachSession」。
    // 下游影响：「AgentConversationSessionEntity.getActorRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getActorRole()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public ActorRole getActorRole() {
        return actorRole;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getAgentKey()」。
    // 具体功能：「AgentConversationSessionEntity.getAgentKey()」：读取「AgentConversationSessionEntity」中的「agentKey」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentConversationSessionEntity.getAgentKey()」的上游调用点包括 「AgentInvocationContext.partyPrivate」。
    // 下游影响：「AgentConversationSessionEntity.getAgentKey()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getAgentKey()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAgentKey() {
        return agentKey;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getAccessSessionId()」。
    // 具体功能：「AgentConversationSessionEntity.getAccessSessionId()」：读取「AgentConversationSessionEntity」中的「accessSessionId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentConversationSessionEntity.getAccessSessionId()」由使用「AgentConversationSessionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentConversationSessionEntity.getAccessSessionId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getAccessSessionId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAccessSessionId() {
        return accessSessionId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getPromptProfileId()」。
    // 具体功能：「AgentConversationSessionEntity.getPromptProfileId()」：读取「AgentConversationSessionEntity」中的「promptProfileId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentConversationSessionEntity.getPromptProfileId()」的上游调用点包括 「AgentInvocationContext.partyPrivate」、「EvidenceContextEnvelopeFactory.actorSnapshot」、「RoomTurnMemoryEntity.attachSession」。
    // 下游影响：「AgentConversationSessionEntity.getPromptProfileId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getPromptProfileId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getPromptProfileId() {
        return promptProfileId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getMemoryPolicyId()」。
    // 具体功能：「AgentConversationSessionEntity.getMemoryPolicyId()」：读取「AgentConversationSessionEntity」中的「memoryPolicyId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentConversationSessionEntity.getMemoryPolicyId()」的上游调用点包括 「AgentInvocationContext.partyPrivate」、「EvidenceContextEnvelopeFactory.actorSnapshot」。
    // 下游影响：「AgentConversationSessionEntity.getMemoryPolicyId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getMemoryPolicyId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getMemoryPolicyId() {
        return memoryPolicyId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.getConversationScope()」。
    // 具体功能：「AgentConversationSessionEntity.getConversationScope()」：读取「AgentConversationSessionEntity」中的「conversationScope」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentConversationSessionEntity.getConversationScope()」的上游调用点包括 「AgentInvocationContext.partyPrivate」、「EvidenceContextEnvelopeFactory.create」、「EvidenceContextEnvelopeFactory.actorSnapshot」、「RoomTurnMemoryEntity.attachSession」。
    // 下游影响：「AgentConversationSessionEntity.getConversationScope()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.getConversationScope()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getConversationScope() {
        return conversationScope;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「AgentConversationSessionEntity.required(String,String)」。
    // 具体功能：「AgentConversationSessionEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「AgentConversationSessionEntity.required(String,String)」的上游调用点包括 「AgentConversationSessionEntity.AgentConversationSessionEntity」、「AgentConversationSessionEntity.create」、「AgentConversationSessionEntity.conversationScope」。
    // 下游影响：「AgentConversationSessionEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentConversationSessionEntity.required(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
