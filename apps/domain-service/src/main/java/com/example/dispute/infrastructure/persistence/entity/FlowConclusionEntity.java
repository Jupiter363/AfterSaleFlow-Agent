/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射FlowConclusion数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「readyForRemedyPlanning」、「prePersist」、「preUpdate」、「getConclusionType」、「getConclusionStatus」、「getConclusionCode」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「FlowConclusionEntity」。
// 类型职责：映射FlowConclusion数据库记录并保存可审计状态；本类型显式提供 「FlowConclusionEntity」、「FlowConclusionEntity」、「readyForRemedyPlanning」、「prePersist」、「preUpdate」、「getConclusionType」。
// 协作关系：主要由 「CaseOutcomeService.finalDecision」、「RouterApplicationService.createConclusion」、「RouterApplicationService.toView」、「RemedyApplicationServiceIntegrationTest.seedFlow」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "flow_conclusion")
public class FlowConclusionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false, unique = true)
    private String caseId;

    @Column(name = "route_decision_id", length = 64, nullable = false, unique = true)
    private String routeDecisionId;

    @Column(name = "conclusion_type", length = 64, nullable = false)
    private String conclusionType;

    @Column(name = "conclusion_status", length = 64, nullable = false)
    private String conclusionStatus;

    @Column(name = "conclusion_code", length = 128, nullable = false)
    private String conclusionCode;

    @Column(name = "summary", nullable = false, columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommended_actions_json", nullable = false, columnDefinition = "jsonb")
    private String recommendedActionsJson;

    @Column(name = "policy_rule_id", length = 64)
    private String policyRuleId;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "requires_remedy_planning", nullable = false)
    private boolean requiresRemedyPlanning;

    @Column(name = "requires_human_review", nullable = false)
    private boolean requiresHumanReview;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.FlowConclusionEntity()」。
    // 具体功能：「FlowConclusionEntity.FlowConclusionEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「FlowConclusionEntity.FlowConclusionEntity()」的上游创建点包括 「FlowConclusionEntity.readyForRemedyPlanning」。
    // 下游影响：「FlowConclusionEntity.FlowConclusionEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FlowConclusionEntity.FlowConclusionEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected FlowConclusionEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.FlowConclusionEntity(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」。
    // 具体功能：「FlowConclusionEntity.FlowConclusionEntity(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」：使用 「id」(String)、「caseId」(String)、「routeDecisionId」(String)、「conclusionType」(String)、「conclusionCode」(String)、「summary」(String)、「recommendedActionsJson」(String)、「policyRuleId」(String)、「policyVersion」(Integer)、「riskLevel」(RiskLevel)、「actorId」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「FlowConclusionEntity.FlowConclusionEntity(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」的上游创建点包括 「FlowConclusionEntity.readyForRemedyPlanning」。
    // 下游影响：「FlowConclusionEntity.FlowConclusionEntity(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FlowConclusionEntity.FlowConclusionEntity(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private FlowConclusionEntity(
            String id,
            String caseId,
            String routeDecisionId,
            String conclusionType,
            String conclusionCode,
            String summary,
            String recommendedActionsJson,
            String policyRuleId,
            Integer policyVersion,
            RiskLevel riskLevel,
            String actorId) {
        super(id);
        this.caseId = caseId;
        this.routeDecisionId = routeDecisionId;
        this.conclusionType = conclusionType;
        this.conclusionStatus = "READY_FOR_REMEDY_PLANNING";
        this.conclusionCode = conclusionCode;
        this.summary = summary;
        this.recommendedActionsJson = recommendedActionsJson;
        this.policyRuleId = policyRuleId;
        this.policyVersion = policyVersion;
        this.riskLevel = riskLevel;
        this.requiresRemedyPlanning = true;
        this.requiresHumanReview = true;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.readyForRemedyPlanning(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」。
    // 具体功能：「FlowConclusionEntity.readyForRemedyPlanning(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」：读取ready面向补救Planning，最终返回「FlowConclusionEntity」。
    // 上游调用：「FlowConclusionEntity.readyForRemedyPlanning(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」的上游调用点包括 「RouterApplicationService.createConclusion」、「RemedyApplicationServiceIntegrationTest.seedFlow」。
    // 下游影响：「FlowConclusionEntity.readyForRemedyPlanning(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「FlowConclusionEntity」交给调用方。
    // 系统意义：「FlowConclusionEntity.readyForRemedyPlanning(String,String,String,String,String,String,String,String,Integer,RiskLevel,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static FlowConclusionEntity readyForRemedyPlanning(
            String id,
            String caseId,
            String routeDecisionId,
            String conclusionType,
            String conclusionCode,
            String summary,
            String recommendedActionsJson,
            String policyRuleId,
            Integer policyVersion,
            RiskLevel riskLevel,
            String actorId) {
        return new FlowConclusionEntity(
                id,
                caseId,
                routeDecisionId,
                conclusionType,
                conclusionCode,
                summary,
                recommendedActionsJson,
                policyRuleId,
                policyVersion,
                riskLevel,
                actorId);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.prePersist()」。
    // 具体功能：「FlowConclusionEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「FlowConclusionEntity.prePersist()」由使用「FlowConclusionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FlowConclusionEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FlowConclusionEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.preUpdate()」。
    // 具体功能：「FlowConclusionEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「FlowConclusionEntity.preUpdate()」由使用「FlowConclusionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「FlowConclusionEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FlowConclusionEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.getConclusionType()」。
    // 具体功能：「FlowConclusionEntity.getConclusionType()」：读取「FlowConclusionEntity」中的「conclusionType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FlowConclusionEntity.getConclusionType()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.getConclusionType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FlowConclusionEntity.getConclusionType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getConclusionType() {
        return conclusionType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.getConclusionStatus()」。
    // 具体功能：「FlowConclusionEntity.getConclusionStatus()」：读取「FlowConclusionEntity」中的「conclusionStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FlowConclusionEntity.getConclusionStatus()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.getConclusionStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FlowConclusionEntity.getConclusionStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getConclusionStatus() {
        return conclusionStatus;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.getConclusionCode()」。
    // 具体功能：「FlowConclusionEntity.getConclusionCode()」：读取「FlowConclusionEntity」中的「conclusionCode」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FlowConclusionEntity.getConclusionCode()」的上游调用点包括 「CaseOutcomeService.finalDecision」、「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.getConclusionCode()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FlowConclusionEntity.getConclusionCode()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getConclusionCode() {
        return conclusionCode;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.getSummary()」。
    // 具体功能：「FlowConclusionEntity.getSummary()」：读取「FlowConclusionEntity」中的「summary」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FlowConclusionEntity.getSummary()」的上游调用点包括 「CaseOutcomeService.finalDecision」、「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.getSummary()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FlowConclusionEntity.getSummary()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSummary() {
        return summary;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.getRecommendedActionsJson()」。
    // 具体功能：「FlowConclusionEntity.getRecommendedActionsJson()」：读取「FlowConclusionEntity」中的「recommendedActionsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FlowConclusionEntity.getRecommendedActionsJson()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.getRecommendedActionsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FlowConclusionEntity.getRecommendedActionsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRecommendedActionsJson() {
        return recommendedActionsJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.getPolicyRuleId()」。
    // 具体功能：「FlowConclusionEntity.getPolicyRuleId()」：读取「FlowConclusionEntity」中的「policyRuleId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「FlowConclusionEntity.getPolicyRuleId()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.getPolicyRuleId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「FlowConclusionEntity.getPolicyRuleId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPolicyRuleId() {
        return policyRuleId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.getPolicyVersion()」。
    // 具体功能：「FlowConclusionEntity.getPolicyVersion()」：读取「FlowConclusionEntity」中的「policyVersion」状态，向 JPA、应用服务或序列化层返回「Integer」。
    // 上游调用：「FlowConclusionEntity.getPolicyVersion()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.getPolicyVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Integer」交给调用方。
    // 系统意义：「FlowConclusionEntity.getPolicyVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public Integer getPolicyVersion() {
        return policyVersion;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.getRiskLevel()」。
    // 具体功能：「FlowConclusionEntity.getRiskLevel()」：读取「FlowConclusionEntity」中的「riskLevel」状态，向 JPA、应用服务或序列化层返回「RiskLevel」。
    // 上游调用：「FlowConclusionEntity.getRiskLevel()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.getRiskLevel()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RiskLevel」交给调用方。
    // 系统意义：「FlowConclusionEntity.getRiskLevel()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.isRequiresRemedyPlanning()」。
    // 具体功能：「FlowConclusionEntity.isRequiresRemedyPlanning()」：判断是否Requires补救Planning，最终返回「boolean」。
    // 上游调用：「FlowConclusionEntity.isRequiresRemedyPlanning()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.isRequiresRemedyPlanning()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「FlowConclusionEntity.isRequiresRemedyPlanning()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public boolean isRequiresRemedyPlanning() {
        return requiresRemedyPlanning;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「FlowConclusionEntity.isRequiresHumanReview()」。
    // 具体功能：「FlowConclusionEntity.isRequiresHumanReview()」：判断是否Requires人工审核，最终返回「boolean」。
    // 上游调用：「FlowConclusionEntity.isRequiresHumanReview()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「FlowConclusionEntity.isRequiresHumanReview()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「FlowConclusionEntity.isRequiresHumanReview()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public boolean isRequiresHumanReview() {
        return requiresHumanReview;
    }
}
