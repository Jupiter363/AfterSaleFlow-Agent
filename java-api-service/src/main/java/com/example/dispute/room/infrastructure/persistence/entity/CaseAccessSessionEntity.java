/*
 * 所属模块：房间协作与权限。
 * 文件职责：映射案件访问会话数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「permissionScopes」、「has」、「privileged」、「getTenantId」、「getCaseId」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.PermissionScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【房间协作与权限 / JPA 实体层】类型「CaseAccessSessionEntity」。
// 类型职责：映射案件访问会话数据库记录并保存可审计状态；本类型显式提供 「CaseAccessSessionEntity」、「CaseAccessSessionEntity」、「create」、「create」、「permissionScopes」、「has」。
// 协作关系：主要由 「AccessSessionInitializer.initialize」、「AgentConversationSessionEntity.create」、「AgentInvocationContext.partyPrivate」、「AgentRunStreamEventService.visibleTo」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "case_access_session")
public class CaseAccessSessionEntity extends AbstractEntity {

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", length = 64, nullable = false)
    private ActorRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_level", length = 64, nullable = false)
    private PermissionLevel permissionLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permission_scopes_json", nullable = false, columnDefinition = "jsonb")
    private String permissionScopesJson;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Transient private Set<PermissionScope> explicitScopes;

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.CaseAccessSessionEntity()」。
    // 具体功能：「CaseAccessSessionEntity.CaseAccessSessionEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseAccessSessionEntity.CaseAccessSessionEntity()」的上游创建点包括 「CaseAccessSessionEntity.create」。
    // 下游影响：「CaseAccessSessionEntity.CaseAccessSessionEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseAccessSessionEntity.CaseAccessSessionEntity()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected CaseAccessSessionEntity() {}

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.CaseAccessSessionEntity(String)」。
    // 具体功能：「CaseAccessSessionEntity.CaseAccessSessionEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseAccessSessionEntity.CaseAccessSessionEntity(String)」的上游创建点包括 「CaseAccessSessionEntity.create」。
    // 下游影响：「CaseAccessSessionEntity.CaseAccessSessionEntity(String)」向下依次触达 「required」。
    // 系统意义：「CaseAccessSessionEntity.CaseAccessSessionEntity(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private CaseAccessSessionEntity(String id) {
        super(required(id, "id"));
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,String)」。
    // 具体功能：「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,String)」：提供「create」的便捷重载：接收 「id」(String)、「tenantId」(String)、「caseId」(String)、「actorId」(String)、「actorRole」(ActorRole)、「permissionLevel」(PermissionLevel)、「createdBy」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,String)」的上游调用点包括 「AccessSessionInitializer.initialize」、「CaseAccessSessionEntity.create」、「AgentRunStreamEventServiceTest.session」、「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession」。
    // 下游影响：「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,String)」向下依次触达 「permissionLevel.defaultScopes」、「create」；计算结果以「CaseAccessSessionEntity」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CaseAccessSessionEntity create(
            String id,
            String tenantId,
            String caseId,
            String actorId,
            ActorRole actorRole,
            PermissionLevel permissionLevel,
            String createdBy) {
        return create(
                id,
                tenantId,
                caseId,
                actorId,
                actorRole,
                permissionLevel,
                permissionLevel.defaultScopes(),
                createdBy);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,Set,String)」。
    // 具体功能：「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,Set,String)」：创建案件访问会话：先更新内部状态 「tenantId」、「caseId」、「actorId」、「actorRole」；实际协作者为 「Objects.requireNonNull」、「Collections.unmodifiableSet」、「permissionLevel.defaultScopes」、「required」；处理的关键状态/协议值包括 「tenantId」、「caseId」、「actorId」、「ACTIVE」，最终返回「CaseAccessSessionEntity」。
    // 上游调用：「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,Set,String)」的上游调用点包括 「AccessSessionInitializer.initialize」、「CaseAccessSessionEntity.create」、「AgentRunStreamEventServiceTest.session」、「AccessSessionResolverTest.resolvesUserOwnerToPartyUserAccessSession」。
    // 下游影响：「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,Set,String)」向下依次触达 「Objects.requireNonNull」、「Collections.unmodifiableSet」、「permissionLevel.defaultScopes」、「required」；计算结果以「CaseAccessSessionEntity」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.create(String,String,String,String,ActorRole,PermissionLevel,Set,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CaseAccessSessionEntity create(
            String id,
            String tenantId,
            String caseId,
            String actorId,
            ActorRole actorRole,
            PermissionLevel permissionLevel,
            Set<PermissionScope> scopes,
            String createdBy) {
        CaseAccessSessionEntity entity = new CaseAccessSessionEntity(id);
        entity.tenantId = required(tenantId, "tenantId");
        entity.caseId = required(caseId, "caseId");
        entity.actorId = required(actorId, "actorId");
        entity.actorRole = Objects.requireNonNull(actorRole, "actorRole must not be null");
        entity.permissionLevel =
                Objects.requireNonNull(permissionLevel, "permissionLevel must not be null");
        entity.explicitScopes =
                Collections.unmodifiableSet(
                        scopes == null || scopes.isEmpty()
                                ? permissionLevel.defaultScopes()
                                : EnumSet.copyOf(scopes));
        entity.permissionScopesJson = renderScopes(entity.explicitScopes);
        entity.status = "ACTIVE";
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.createdBy = required(createdBy, "createdBy");
        return entity;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.permissionScopes()」。
    // 具体功能：「CaseAccessSessionEntity.permissionScopes()」：构建权限权限范围；实际协作者为 「permissionLevel.defaultScopes」，最终返回「Set<PermissionScope>」。
    // 上游调用：「CaseAccessSessionEntity.permissionScopes()」的上游调用点包括 「AgentInvocationContext.partyPrivate」、「CaseAccessSessionEntity.has」。
    // 下游影响：「CaseAccessSessionEntity.permissionScopes()」向下依次触达 「permissionLevel.defaultScopes」；计算结果以「Set<PermissionScope>」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.permissionScopes()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public Set<PermissionScope> permissionScopes() {
        if (explicitScopes != null) {
            return explicitScopes;
        }
        return permissionLevel.defaultScopes();
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.has(PermissionScope)」。
    // 具体功能：「CaseAccessSessionEntity.has(PermissionScope)」：判断是否存在；实际协作者为 「permissionScopes」，最终返回「boolean」。
    // 上游调用：「CaseAccessSessionEntity.has(PermissionScope)」的上游调用点包括 「SessionPermissionService.require」。
    // 下游影响：「CaseAccessSessionEntity.has(PermissionScope)」向下依次触达 「permissionScopes」；计算结果以「boolean」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.has(PermissionScope)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public boolean has(PermissionScope scope) {
        return permissionScopes().contains(scope);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.privileged()」。
    // 具体功能：「CaseAccessSessionEntity.privileged()」：判断高权限级别；实际协作者为 「permissionLevel.privileged」，最终返回「boolean」。
    // 上游调用：「CaseAccessSessionEntity.privileged()」的上游调用点包括 「EvidenceAgentTurnService.evidenceVisibleToSession」、「EvidenceAgentTurnService.visibleToAccessSession」、「SessionPermissionService.requirePartyPrivateSessionRead」、「SessionPermissionService.canReadActorAudience」。
    // 下游影响：「CaseAccessSessionEntity.privileged()」向下依次触达 「permissionLevel.privileged」；计算结果以「boolean」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.privileged()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public boolean privileged() {
        return permissionLevel.privileged();
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.getTenantId()」。
    // 具体功能：「CaseAccessSessionEntity.getTenantId()」：读取「CaseAccessSessionEntity」中的「tenantId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseAccessSessionEntity.getTenantId()」的上游调用点包括 「AgentInvocationContext.partyPrivate」、「AgentSessionInitializer.initialize」、「AgentSessionResolver.find」、「AgentConversationSessionEntity.create」。
    // 下游影响：「CaseAccessSessionEntity.getTenantId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.getTenantId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getTenantId() {
        return tenantId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.getCaseId()」。
    // 具体功能：「CaseAccessSessionEntity.getCaseId()」：读取「CaseAccessSessionEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseAccessSessionEntity.getCaseId()」的上游调用点包括 「AgentInvocationContext.partyPrivate」、「AgentSessionInitializer.initialize」、「AgentSessionResolver.find」、「AgentConversationSessionEntity.create」。
    // 下游影响：「CaseAccessSessionEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.getCaseId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.getActorId()」。
    // 具体功能：「CaseAccessSessionEntity.getActorId()」：读取「CaseAccessSessionEntity」中的「actorId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseAccessSessionEntity.getActorId()」的上游调用点包括 「AgentInvocationContext.partyPrivate」、「AgentSessionInitializer.initialize」、「AgentSessionResolver.find」、「EvidenceAgentTurnService.evidenceVisibleToSession」。
    // 下游影响：「CaseAccessSessionEntity.getActorId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.getActorId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getActorId() {
        return actorId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.getActorRole()」。
    // 具体功能：「CaseAccessSessionEntity.getActorRole()」：读取「CaseAccessSessionEntity」中的「actorRole」状态，向 JPA、应用服务或序列化层返回「ActorRole」。
    // 上游调用：「CaseAccessSessionEntity.getActorRole()」的上游调用点包括 「AgentRunStreamEventService.visibleTo」、「AgentInvocationContext.partyPrivate」、「AgentSessionInitializer.initialize」、「AgentSessionResolver.find」。
    // 下游影响：「CaseAccessSessionEntity.getActorRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.getActorRole()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public ActorRole getActorRole() {
        return actorRole;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.getPermissionLevel()」。
    // 具体功能：「CaseAccessSessionEntity.getPermissionLevel()」：读取「CaseAccessSessionEntity」中的「permissionLevel」状态，向 JPA、应用服务或序列化层返回「PermissionLevel」。
    // 上游调用：「CaseAccessSessionEntity.getPermissionLevel()」的上游调用点包括 「AgentInvocationContext.partyPrivate」。
    // 下游影响：「CaseAccessSessionEntity.getPermissionLevel()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「PermissionLevel」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.getPermissionLevel()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public PermissionLevel getPermissionLevel() {
        return permissionLevel;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.getPermissionScopesJson()」。
    // 具体功能：「CaseAccessSessionEntity.getPermissionScopesJson()」：读取「CaseAccessSessionEntity」中的「permissionScopesJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseAccessSessionEntity.getPermissionScopesJson()」由使用「CaseAccessSessionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseAccessSessionEntity.getPermissionScopesJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.getPermissionScopesJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getPermissionScopesJson() {
        return permissionScopesJson;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.renderScopes(Set)」。
    // 具体功能：「CaseAccessSessionEntity.renderScopes(Set)」：序列化权限范围；实际协作者为 「json.append」、「json.append('"').append(scope.name()).append」、「json.append('"').append」；处理的关键状态/协议值包括 「[」，最终返回「String」。
    // 上游调用：「CaseAccessSessionEntity.renderScopes(Set)」的上游调用点包括 「CaseAccessSessionEntity.create」。
    // 下游影响：「CaseAccessSessionEntity.renderScopes(Set)」向下依次触达 「json.append」、「json.append('"').append(scope.name()).append」、「json.append('"').append」；计算结果以「String」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.renderScopes(Set)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String renderScopes(Set<PermissionScope> scopes) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (PermissionScope scope : scopes) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(scope.name()).append('"');
            first = false;
        }
        return json.append(']').toString();
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseAccessSessionEntity.required(String,String)」。
    // 具体功能：「CaseAccessSessionEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「CaseAccessSessionEntity.required(String,String)」的上游调用点包括 「CaseAccessSessionEntity.CaseAccessSessionEntity」、「CaseAccessSessionEntity.create」。
    // 下游影响：「CaseAccessSessionEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseAccessSessionEntity.required(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
