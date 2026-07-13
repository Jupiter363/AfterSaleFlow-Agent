/*
 * 所属模块：共享小法庭。
 * 文件职责：映射和解Proposal数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「propose」、「supersede」、「confirm」、「getCaseId」、「getProposalVersion」、「getProposalStatus」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.SettlementStatus;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【共享小法庭 / JPA 实体层】类型「SettlementProposalEntity」。
// 类型职责：映射和解Proposal数据库记录并保存可审计状态；本类型显式提供 「SettlementProposalEntity」、「SettlementProposalEntity」、「propose」、「supersede」、「confirm」、「getCaseId」。
// 协作关系：主要由 「SettlementService.propose」、「SettlementService.view」、「FinalWorkflowActivitiesAdapterTest.completedSharedHearingCreatesReviewTaskForHumanGate」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "settlement_proposal")
public class SettlementProposalEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "proposal_version", nullable = false)
    private int proposalVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "proposal_status", length = 32, nullable = false)
    private SettlementStatus proposalStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "proposed_by_role", length = 32, nullable = false)
    private ActorRole proposedByRole;
    @Column(name = "proposed_by_id", length = 128, nullable = false)
    private String proposedById;
    @Column(name = "proposal_text", nullable = false, columnDefinition = "text")
    private String proposalText;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal_json", nullable = false, columnDefinition = "jsonb")
    private String proposalJson;
    @Column(name = "supersedes_proposal_id", length = 64)
    private String supersedesProposalId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "trace_id", length = 128)
    private String traceId;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.SettlementProposalEntity()」。
    // 具体功能：「SettlementProposalEntity.SettlementProposalEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「SettlementProposalEntity.SettlementProposalEntity()」的上游创建点包括 「SettlementProposalEntity.propose」。
    // 下游影响：「SettlementProposalEntity.SettlementProposalEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SettlementProposalEntity.SettlementProposalEntity()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected SettlementProposalEntity() {}

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.SettlementProposalEntity(String)」。
    // 具体功能：「SettlementProposalEntity.SettlementProposalEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「SettlementProposalEntity.SettlementProposalEntity(String)」的上游创建点包括 「SettlementProposalEntity.propose」。
    // 下游影响：「SettlementProposalEntity.SettlementProposalEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SettlementProposalEntity.SettlementProposalEntity(String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private SettlementProposalEntity(String id) {
        super(id);
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.propose(String,String,int,ActorRole,String,String,String,String,Instant,String)」。
    // 具体功能：「SettlementProposalEntity.propose(String,String,int,ActorRole,String,String,String,String,Instant,String)」：更新和解提案：先更新内部状态 「caseId」、「proposalVersion」、「proposalStatus」、「proposedByRole」，最终返回「SettlementProposalEntity」。
    // 上游调用：「SettlementProposalEntity.propose(String,String,int,ActorRole,String,String,String,String,Instant,String)」的上游调用点包括 「SettlementService.propose」、「FinalWorkflowActivitiesAdapterTest.completedSharedHearingCreatesReviewTaskForHumanGate」。
    // 下游影响：「SettlementProposalEntity.propose(String,String,int,ActorRole,String,String,String,String,Instant,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「SettlementProposalEntity」交给调用方。
    // 系统意义：「SettlementProposalEntity.propose(String,String,int,ActorRole,String,String,String,String,Instant,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public static SettlementProposalEntity propose(
            String id,
            String caseId,
            int version,
            ActorRole role,
            String actorId,
            String proposalText,
            String proposalJson,
            String supersedesProposalId,
            Instant now,
            String traceId) {
        SettlementProposalEntity entity = new SettlementProposalEntity(id);
        entity.caseId = caseId;
        entity.proposalVersion = version;
        entity.proposalStatus = SettlementStatus.PENDING_CONFIRMATION;
        entity.proposedByRole = role;
        entity.proposedById = actorId;
        entity.proposalText = proposalText;
        entity.proposalJson = proposalJson;
        entity.supersedesProposalId = supersedesProposalId;
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.traceId = traceId;
        entity.createdBy = actorId;
        entity.updatedBy = actorId;
        return entity;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.supersede(String,Instant)」。
    // 具体功能：「SettlementProposalEntity.supersede(String,Instant)」：更新supersede：先更新内部状态 「proposalStatus」、「updatedAt」、「updatedBy」；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「SettlementProposalEntity.supersede(String,Instant)」由使用「SettlementProposalEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SettlementProposalEntity.supersede(String,Instant)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SettlementProposalEntity.supersede(String,Instant)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void supersede(String actorId, Instant now) {
        if (proposalStatus == SettlementStatus.CONFIRMED) {
            throw new IllegalStateException("confirmed settlement cannot be superseded");
        }
        proposalStatus = SettlementStatus.SUPERSEDED;
        updatedAt = now;
        updatedBy = actorId;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.confirm(String,Instant)」。
    // 具体功能：「SettlementProposalEntity.confirm(String,Instant)」：确认和解Proposal：先更新内部状态 「proposalStatus」、「updatedAt」、「updatedBy」；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「SettlementProposalEntity.confirm(String,Instant)」由使用「SettlementProposalEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「SettlementProposalEntity.confirm(String,Instant)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SettlementProposalEntity.confirm(String,Instant)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void confirm(String actorId, Instant now) {
        if (proposalStatus != SettlementStatus.PENDING_CONFIRMATION) {
            throw new IllegalStateException("settlement cannot be confirmed from " + proposalStatus);
        }
        proposalStatus = SettlementStatus.CONFIRMED;
        updatedAt = now;
        updatedBy = actorId;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.getCaseId()」。
    // 具体功能：「SettlementProposalEntity.getCaseId()」：读取「SettlementProposalEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「SettlementProposalEntity.getCaseId()」的上游调用点包括 「SettlementService.view」。
    // 下游影响：「SettlementProposalEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「SettlementProposalEntity.getCaseId()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getCaseId() { return caseId; }
    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.getProposalVersion()」。
    // 具体功能：「SettlementProposalEntity.getProposalVersion()」：读取「SettlementProposalEntity」中的「proposalVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「SettlementProposalEntity.getProposalVersion()」的上游调用点包括 「SettlementService.view」。
    // 下游影响：「SettlementProposalEntity.getProposalVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「SettlementProposalEntity.getProposalVersion()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public int getProposalVersion() { return proposalVersion; }
    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.getProposalStatus()」。
    // 具体功能：「SettlementProposalEntity.getProposalStatus()」：读取「SettlementProposalEntity」中的「proposalStatus」状态，向 JPA、应用服务或序列化层返回「SettlementStatus」。
    // 上游调用：「SettlementProposalEntity.getProposalStatus()」的上游调用点包括 「SettlementService.view」。
    // 下游影响：「SettlementProposalEntity.getProposalStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「SettlementStatus」交给调用方。
    // 系统意义：「SettlementProposalEntity.getProposalStatus()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public SettlementStatus getProposalStatus() { return proposalStatus; }
    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.getProposedByRole()」。
    // 具体功能：「SettlementProposalEntity.getProposedByRole()」：读取「SettlementProposalEntity」中的「proposedByRole」状态，向 JPA、应用服务或序列化层返回「ActorRole」。
    // 上游调用：「SettlementProposalEntity.getProposedByRole()」的上游调用点包括 「SettlementService.view」。
    // 下游影响：「SettlementProposalEntity.getProposedByRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「SettlementProposalEntity.getProposedByRole()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public ActorRole getProposedByRole() { return proposedByRole; }
    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.getProposalText()」。
    // 具体功能：「SettlementProposalEntity.getProposalText()」：读取「SettlementProposalEntity」中的「proposalText」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「SettlementProposalEntity.getProposalText()」的上游调用点包括 「SettlementService.view」。
    // 下游影响：「SettlementProposalEntity.getProposalText()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「SettlementProposalEntity.getProposalText()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getProposalText() { return proposalText; }
    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.getProposalJson()」。
    // 具体功能：「SettlementProposalEntity.getProposalJson()」：读取「SettlementProposalEntity」中的「proposalJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「SettlementProposalEntity.getProposalJson()」的上游调用点包括 「SettlementService.view」。
    // 下游影响：「SettlementProposalEntity.getProposalJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「SettlementProposalEntity.getProposalJson()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getProposalJson() { return proposalJson; }
    // 所属模块：【共享小法庭 / JPA 实体层】「SettlementProposalEntity.getCreatedAt()」。
    // 具体功能：「SettlementProposalEntity.getCreatedAt()」：读取「SettlementProposalEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「SettlementProposalEntity.getCreatedAt()」的上游调用点包括 「SettlementService.view」。
    // 下游影响：「SettlementProposalEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「SettlementProposalEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public Instant getCreatedAt() { return createdAt; }
}
