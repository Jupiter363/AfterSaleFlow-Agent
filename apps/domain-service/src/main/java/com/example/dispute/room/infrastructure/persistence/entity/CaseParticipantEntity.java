/*
 * 所属模块：房间协作与权限。
 * 文件职责：映射案件参与人数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「active」、「invited」、「prePersist」、「preUpdate」、「getCaseId」、「getActorId」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.ParticipantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【房间协作与权限 / JPA 实体层】类型「CaseParticipantEntity」。
// 类型职责：映射案件参与人数据库记录并保存可审计状态；本类型显式提供 「CaseParticipantEntity」、「CaseParticipantEntity」、「active」、「invited」、「create」、「prePersist」。
// 协作关系：主要由 「ParticipantService.ensureImportedParties」、「ParticipantService.participant」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "case_participant")
public class CaseParticipantEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32, nullable = false)
    private ActorRole participantRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_status", length = 32, nullable = false)
    private ParticipantStatus participantStatus;

    @Column(name = "joined_at")
    private OffsetDateTime joinedAt;

    @Column(name = "invited_at")
    private OffsetDateTime invitedAt;

    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visibility_scope_json", nullable = false, columnDefinition = "jsonb")
    private String visibilityScopeJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.CaseParticipantEntity()」。
    // 具体功能：「CaseParticipantEntity.CaseParticipantEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseParticipantEntity.CaseParticipantEntity()」的上游创建点包括 「CaseParticipantEntity.create」。
    // 下游影响：「CaseParticipantEntity.CaseParticipantEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseParticipantEntity.CaseParticipantEntity()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected CaseParticipantEntity() {}

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.CaseParticipantEntity(String)」。
    // 具体功能：「CaseParticipantEntity.CaseParticipantEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseParticipantEntity.CaseParticipantEntity(String)」的上游创建点包括 「CaseParticipantEntity.create」。
    // 下游影响：「CaseParticipantEntity.CaseParticipantEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseParticipantEntity.CaseParticipantEntity(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private CaseParticipantEntity(String id) {
        super(id);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.active(String,String,String,ActorRole,OffsetDateTime,String)」。
    // 具体功能：「CaseParticipantEntity.active(String,String,String,ActorRole,OffsetDateTime,String)」：查询活动状态案件参与人；实际协作者为 「create」，最终返回「CaseParticipantEntity」。
    // 上游调用：「CaseParticipantEntity.active(String,String,String,ActorRole,OffsetDateTime,String)」的上游调用点包括 「ParticipantService.participant」。
    // 下游影响：「CaseParticipantEntity.active(String,String,String,ActorRole,OffsetDateTime,String)」向下依次触达 「create」；计算结果以「CaseParticipantEntity」交给调用方。
    // 系统意义：「CaseParticipantEntity.active(String,String,String,ActorRole,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CaseParticipantEntity active(
            String id,
            String caseId,
            String actorId,
            ActorRole role,
            OffsetDateTime now,
            String createdBy) {
        return create(
                id,
                caseId,
                actorId,
                role,
                ParticipantStatus.ACTIVE,
                now,
                now,
                createdBy);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.invited(String,String,String,ActorRole,OffsetDateTime,String)」。
    // 具体功能：「CaseParticipantEntity.invited(String,String,String,ActorRole,OffsetDateTime,String)」：邀请受邀参与人；实际协作者为 「create」，最终返回「CaseParticipantEntity」。
    // 上游调用：「CaseParticipantEntity.invited(String,String,String,ActorRole,OffsetDateTime,String)」的上游调用点包括 「ParticipantService.ensureImportedParties」、「ParticipantService.participant」。
    // 下游影响：「CaseParticipantEntity.invited(String,String,String,ActorRole,OffsetDateTime,String)」向下依次触达 「create」；计算结果以「CaseParticipantEntity」交给调用方。
    // 系统意义：「CaseParticipantEntity.invited(String,String,String,ActorRole,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CaseParticipantEntity invited(
            String id,
            String caseId,
            String actorId,
            ActorRole role,
            OffsetDateTime now,
            String createdBy) {
        return create(
                id,
                caseId,
                actorId,
                role,
                ParticipantStatus.INVITED,
                null,
                now,
                createdBy);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.create(String,String,String,ActorRole,ParticipantStatus,OffsetDateTime,OffsetDateTime,String)」。
    // 具体功能：「CaseParticipantEntity.create(String,String,String,ActorRole,ParticipantStatus,OffsetDateTime,OffsetDateTime,String)」：创建案件参与人：先更新内部状态 「caseId」、「actorId」、「participantRole」、「participantStatus」；实际协作者为 「Objects.requireNonNull」、「required」；处理的关键状态/协议值包括 「id」、「caseId」、「actorId」、「{}」，最终返回「CaseParticipantEntity」。
    // 上游调用：「CaseParticipantEntity.create(String,String,String,ActorRole,ParticipantStatus,OffsetDateTime,OffsetDateTime,String)」的上游调用点包括 「CaseParticipantEntity.active」、「CaseParticipantEntity.invited」。
    // 下游影响：「CaseParticipantEntity.create(String,String,String,ActorRole,ParticipantStatus,OffsetDateTime,OffsetDateTime,String)」向下依次触达 「Objects.requireNonNull」、「required」；计算结果以「CaseParticipantEntity」交给调用方。
    // 系统意义：「CaseParticipantEntity.create(String,String,String,ActorRole,ParticipantStatus,OffsetDateTime,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static CaseParticipantEntity create(
            String id,
            String caseId,
            String actorId,
            ActorRole role,
            ParticipantStatus status,
            OffsetDateTime joinedAt,
            OffsetDateTime invitedAt,
            String createdBy) {
        CaseParticipantEntity entity = new CaseParticipantEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.actorId = required(actorId, "actorId");
        entity.participantRole = Objects.requireNonNull(role, "role must not be null");
        entity.participantStatus = status;
        entity.joinedAt = joinedAt;
        entity.invitedAt = invitedAt;
        entity.visibilityScopeJson = "{}";
        entity.createdBy = required(createdBy, "createdBy");
        entity.updatedBy = createdBy;
        return entity;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.prePersist()」。
    // 具体功能：「CaseParticipantEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「CaseParticipantEntity.prePersist()」由使用「CaseParticipantEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseParticipantEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseParticipantEntity.prePersist()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.preUpdate()」。
    // 具体功能：「CaseParticipantEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「CaseParticipantEntity.preUpdate()」由使用「CaseParticipantEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseParticipantEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseParticipantEntity.preUpdate()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.getCaseId()」。
    // 具体功能：「CaseParticipantEntity.getCaseId()」：读取「CaseParticipantEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseParticipantEntity.getCaseId()」由使用「CaseParticipantEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseParticipantEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseParticipantEntity.getCaseId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.getActorId()」。
    // 具体功能：「CaseParticipantEntity.getActorId()」：读取「CaseParticipantEntity」中的「actorId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseParticipantEntity.getActorId()」由使用「CaseParticipantEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseParticipantEntity.getActorId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseParticipantEntity.getActorId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getActorId() {
        return actorId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.getParticipantRole()」。
    // 具体功能：「CaseParticipantEntity.getParticipantRole()」：读取「CaseParticipantEntity」中的「participantRole」状态，向 JPA、应用服务或序列化层返回「ActorRole」。
    // 上游调用：「CaseParticipantEntity.getParticipantRole()」由使用「CaseParticipantEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseParticipantEntity.getParticipantRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「CaseParticipantEntity.getParticipantRole()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public ActorRole getParticipantRole() {
        return participantRole;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.getParticipantStatus()」。
    // 具体功能：「CaseParticipantEntity.getParticipantStatus()」：读取「CaseParticipantEntity」中的「participantStatus」状态，向 JPA、应用服务或序列化层返回「ParticipantStatus」。
    // 上游调用：「CaseParticipantEntity.getParticipantStatus()」由使用「CaseParticipantEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseParticipantEntity.getParticipantStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ParticipantStatus」交给调用方。
    // 系统意义：「CaseParticipantEntity.getParticipantStatus()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public ParticipantStatus getParticipantStatus() {
        return participantStatus;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.activate(OffsetDateTime,String)」。
    // 具体功能：「CaseParticipantEntity.activate(OffsetDateTime,String)」：更新activate：先更新内部状态 「participantStatus」、「joinedAt」、「leftAt」、「updatedBy」；实际协作者为 「Objects.requireNonNull」、「required」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「CaseParticipantEntity.activate(OffsetDateTime,String)」由使用「CaseParticipantEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseParticipantEntity.activate(OffsetDateTime,String)」向下依次触达 「Objects.requireNonNull」、「required」。
    // 系统意义：「CaseParticipantEntity.activate(OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void activate(OffsetDateTime now, String actorId) {
        participantStatus = ParticipantStatus.ACTIVE;
        joinedAt = Objects.requireNonNull(now, "now must not be null");
        leftAt = null;
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseParticipantEntity.required(String,String)」。
    // 具体功能：「CaseParticipantEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「CaseParticipantEntity.required(String,String)」的上游调用点包括 「CaseParticipantEntity.create」、「CaseParticipantEntity.activate」。
    // 下游影响：「CaseParticipantEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseParticipantEntity.required(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
