/*
 * 所属模块：共享小法庭。
 * 文件职责：映射和解Confirmation数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「confirmed」、「getProposalId」、「getProposalVersion」、「getParticipantRole」、「getParticipantId」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

// 所属模块：【共享小法庭 / JPA 实体层】类型「SettlementConfirmationEntity」。
// 类型职责：映射和解Confirmation数据库记录并保存可审计状态；本类型显式提供 「SettlementConfirmationEntity」、「SettlementConfirmationEntity」、「confirmed」、「getProposalId」、「getProposalVersion」、「getParticipantRole」。
// 协作关系：主要由 「SettlementService.confirm」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Immutable
@Table(name = "settlement_confirmation")
public class SettlementConfirmationEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "proposal_id", length = 64, nullable = false)
    private String proposalId;
    @Column(name = "proposal_version", nullable = false)
    private int proposalVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32, nullable = false)
    private ActorRole participantRole;
    @Column(name = "participant_id", length = 128, nullable = false)
    private String participantId;
    @Column(name = "confirmation_status", length = 32, nullable = false)
    private String confirmationStatus;
    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;
    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementConfirmationEntity.SettlementConfirmationEntity()」。
    // 具体功能：「SettlementConfirmationEntity.SettlementConfirmationEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「SettlementConfirmationEntity.SettlementConfirmationEntity()」的上游创建点包括 「SettlementConfirmationEntity.confirmed」。
    // 下游影响：「SettlementConfirmationEntity.SettlementConfirmationEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SettlementConfirmationEntity.SettlementConfirmationEntity()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected SettlementConfirmationEntity() {}

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementConfirmationEntity.SettlementConfirmationEntity(String)」。
    // 具体功能：「SettlementConfirmationEntity.SettlementConfirmationEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「SettlementConfirmationEntity.SettlementConfirmationEntity(String)」的上游创建点包括 「SettlementConfirmationEntity.confirmed」。
    // 下游影响：「SettlementConfirmationEntity.SettlementConfirmationEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SettlementConfirmationEntity.SettlementConfirmationEntity(String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private SettlementConfirmationEntity(String id) {
        super(id);
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementConfirmationEntity.confirmed(String,String,String,int,ActorRole,String,String,Instant)」。
    // 具体功能：「SettlementConfirmationEntity.confirmed(String,String,String,int,ActorRole,String,String,Instant)」：确认confirmed：先更新内部状态 「caseId」、「proposalId」、「proposalVersion」、「participantRole」；处理的关键状态/协议值包括 「CONFIRMED」，最终返回「SettlementConfirmationEntity」。
    // 上游调用：「SettlementConfirmationEntity.confirmed(String,String,String,int,ActorRole,String,String,Instant)」的上游调用点包括 「SettlementService.confirm」。
    // 下游影响：「SettlementConfirmationEntity.confirmed(String,String,String,int,ActorRole,String,String,Instant)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「SettlementConfirmationEntity」交给调用方。
    // 系统意义：「SettlementConfirmationEntity.confirmed(String,String,String,int,ActorRole,String,String,Instant)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public static SettlementConfirmationEntity confirmed(
            String id,
            String caseId,
            String proposalId,
            int proposalVersion,
            ActorRole role,
            String actorId,
            String idempotencyKey,
            Instant now) {
        SettlementConfirmationEntity entity = new SettlementConfirmationEntity(id);
        entity.caseId = caseId;
        entity.proposalId = proposalId;
        entity.proposalVersion = proposalVersion;
        entity.participantRole = role;
        entity.participantId = actorId;
        entity.confirmationStatus = "CONFIRMED";
        entity.idempotencyKey = idempotencyKey;
        entity.confirmedAt = now;
        entity.createdAt = now;
        entity.createdBy = actorId;
        return entity;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementConfirmationEntity.getProposalId()」。
    // 具体功能：「SettlementConfirmationEntity.getProposalId()」：读取「SettlementConfirmationEntity」中的「proposalId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「SettlementConfirmationEntity.getProposalId()」由使用「SettlementConfirmationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SettlementConfirmationEntity.getProposalId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「SettlementConfirmationEntity.getProposalId()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getProposalId() {
        return proposalId;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementConfirmationEntity.getProposalVersion()」。
    // 具体功能：「SettlementConfirmationEntity.getProposalVersion()」：读取「SettlementConfirmationEntity」中的「proposalVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「SettlementConfirmationEntity.getProposalVersion()」由使用「SettlementConfirmationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SettlementConfirmationEntity.getProposalVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「SettlementConfirmationEntity.getProposalVersion()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public int getProposalVersion() {
        return proposalVersion;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementConfirmationEntity.getParticipantRole()」。
    // 具体功能：「SettlementConfirmationEntity.getParticipantRole()」：读取「SettlementConfirmationEntity」中的「participantRole」状态，向 JPA、应用服务或序列化层返回「ActorRole」。
    // 上游调用：「SettlementConfirmationEntity.getParticipantRole()」由使用「SettlementConfirmationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SettlementConfirmationEntity.getParticipantRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「SettlementConfirmationEntity.getParticipantRole()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public ActorRole getParticipantRole() {
        return participantRole;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementConfirmationEntity.getParticipantId()」。
    // 具体功能：「SettlementConfirmationEntity.getParticipantId()」：读取「SettlementConfirmationEntity」中的「participantId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「SettlementConfirmationEntity.getParticipantId()」由使用「SettlementConfirmationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SettlementConfirmationEntity.getParticipantId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「SettlementConfirmationEntity.getParticipantId()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getParticipantId() {
        return participantId;
    }
}
