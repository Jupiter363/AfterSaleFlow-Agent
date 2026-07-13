/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射审批决定数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「record」、「prePersist」、「getPolicyVersion」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.review.domain.ApprovalPolicyDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「ApprovalPolicyDecisionEntity」。
// 类型职责：映射审批决定数据库记录并保存可审计状态；本类型显式提供 「ApprovalPolicyDecisionEntity」、「ApprovalPolicyDecisionEntity」、「record」、「prePersist」、「getPolicyVersion」。
// 协作关系：主要由 「ReviewApplicationService.createForWorkflow」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "approval_policy_decision")
public class ApprovalPolicyDecisionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "plan_id", length = 64, nullable = false)
    private String planId;
    @Column(name = "policy_version", length = 64, nullable = false)
    private String policyVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;
    @Column(name = "required_reviewer_role", length = 32, nullable = false)
    private String requiredReviewerRole;
    @Column(name = "required_review_count", nullable = false)
    private int requiredReviewCount;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_actions_json", nullable = false, columnDefinition = "jsonb")
    private String allowedActionsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forbidden_actions_json", nullable = false, columnDefinition = "jsonb")
    private String forbiddenActionsJson;
    @Column(name = "escalation_reason", columnDefinition = "text")
    private String escalationReason;
    @Column(name = "auto_approve", nullable = false)
    private boolean autoApprove;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity()」。
    // 具体功能：「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity()」的上游创建点包括 「ApprovalPolicyDecisionEntity.record」。
    // 下游影响：「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected ApprovalPolicyDecisionEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity(String)」。
    // 具体功能：「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity(String)」的上游创建点包括 「ApprovalPolicyDecisionEntity.record」。
    // 下游影响：「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ApprovalPolicyDecisionEntity.ApprovalPolicyDecisionEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private ApprovalPolicyDecisionEntity(String id) {
        super(id);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalPolicyDecisionEntity.record(String,String,String,RiskLevel,ApprovalPolicyDecision,String,String,String)」。
    // 具体功能：「ApprovalPolicyDecisionEntity.record(String,String,String,RiskLevel,ApprovalPolicyDecision,String,String,String)」：记录审批决定：先更新内部状态 「caseId」、「planId」、「policyVersion」、「riskLevel」；实际协作者为 「String.join」、「decision.policyVersion」、「decision.requiredRole」、「decision.requiredReviewCount」；处理的关键状态/协议值包括 「,」，最终返回「ApprovalPolicyDecisionEntity」。
    // 上游调用：「ApprovalPolicyDecisionEntity.record(String,String,String,RiskLevel,ApprovalPolicyDecision,String,String,String)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」。
    // 下游影响：「ApprovalPolicyDecisionEntity.record(String,String,String,RiskLevel,ApprovalPolicyDecision,String,String,String)」向下依次触达 「String.join」、「decision.policyVersion」、「decision.requiredRole」、「decision.requiredReviewCount」；计算结果以「ApprovalPolicyDecisionEntity」交给调用方。
    // 系统意义：「ApprovalPolicyDecisionEntity.record(String,String,String,RiskLevel,ApprovalPolicyDecision,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ApprovalPolicyDecisionEntity record(
            String id,
            String caseId,
            String planId,
            RiskLevel riskLevel,
            ApprovalPolicyDecision decision,
            String allowedActionsJson,
            String forbiddenActionsJson,
            String actorId) {
        ApprovalPolicyDecisionEntity entity =
                new ApprovalPolicyDecisionEntity(id);
        entity.caseId = caseId;
        entity.planId = planId;
        entity.policyVersion = decision.policyVersion();
        entity.riskLevel = riskLevel;
        entity.requiredReviewerRole = decision.requiredRole();
        entity.requiredReviewCount = decision.requiredReviewCount();
        entity.allowedActionsJson = allowedActionsJson;
        entity.forbiddenActionsJson = forbiddenActionsJson;
        entity.escalationReason =
                decision.riskFlags().isEmpty()
                        ? null
                        : String.join(",", decision.riskFlags());
        entity.autoApprove = decision.autoApprove();
        entity.createdBy = actorId;
        return entity;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalPolicyDecisionEntity.prePersist()」。
    // 具体功能：「ApprovalPolicyDecisionEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「ApprovalPolicyDecisionEntity.prePersist()」由使用「ApprovalPolicyDecisionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ApprovalPolicyDecisionEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ApprovalPolicyDecisionEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalPolicyDecisionEntity.getPolicyVersion()」。
    // 具体功能：「ApprovalPolicyDecisionEntity.getPolicyVersion()」：读取「ApprovalPolicyDecisionEntity」中的「policyVersion」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalPolicyDecisionEntity.getPolicyVersion()」由使用「ApprovalPolicyDecisionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ApprovalPolicyDecisionEntity.getPolicyVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalPolicyDecisionEntity.getPolicyVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPolicyVersion() {
        return policyVersion;
    }
}
