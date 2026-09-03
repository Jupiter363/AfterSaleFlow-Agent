/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射规则数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「active」、「prePersist」、「preUpdate」、「getRuleCode」、「getRuleVersion」、「getRuleName」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「PolicyRuleEntity」。
// 类型职责：映射规则数据库记录并保存可审计状态；本类型显式提供 「PolicyRuleEntity」、「PolicyRuleEntity」、「active」、「prePersist」、「preUpdate」、「getRuleCode」。
// 协作关系：主要由 「PolicyApplicationService.toView」、「RouterApplicationService.createConclusion」、「RuleFlowService.conclude」、「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "policy_rule")
public class PolicyRuleEntity extends AbstractEntity {

    @Column(name = "rule_code", length = 128, nullable = false)
    private String ruleCode;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion;

    @Column(name = "rule_name", length = 256, nullable = false)
    private String ruleName;

    @Column(name = "rule_scope", length = 64, nullable = false)
    private String ruleScope;

    @Column(name = "rule_status", length = 32, nullable = false)
    private String ruleStatus;

    @Column(name = "effective_from", nullable = false)
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    @Column(name = "priority", nullable = false)
    private int priority;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_json", nullable = false, columnDefinition = "jsonb")
    private String conditionJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcome_json", nullable = false, columnDefinition = "jsonb")
    private String outcomeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_document_json", nullable = false, columnDefinition = "jsonb")
    private String sourceDocumentJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.PolicyRuleEntity()」。
    // 具体功能：「PolicyRuleEntity.PolicyRuleEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「PolicyRuleEntity.PolicyRuleEntity()」的上游创建点包括 「PolicyRuleEntity.active」。
    // 下游影响：「PolicyRuleEntity.PolicyRuleEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PolicyRuleEntity.PolicyRuleEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected PolicyRuleEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.PolicyRuleEntity(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」。
    // 具体功能：「PolicyRuleEntity.PolicyRuleEntity(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」：使用 「id」(String)、「ruleCode」(String)、「ruleVersion」(int)、「ruleName」(String)、「ruleScope」(String)、「effectiveFrom」(OffsetDateTime)、「priority」(int)、「conditionJson」(String)、「outcomeJson」(String)、「sourceDocumentJson」(String)、「actorId」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「PolicyRuleEntity.PolicyRuleEntity(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」的上游创建点包括 「PolicyRuleEntity.active」。
    // 下游影响：「PolicyRuleEntity.PolicyRuleEntity(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PolicyRuleEntity.PolicyRuleEntity(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private PolicyRuleEntity(
            String id,
            String ruleCode,
            int ruleVersion,
            String ruleName,
            String ruleScope,
            OffsetDateTime effectiveFrom,
            int priority,
            String conditionJson,
            String outcomeJson,
            String sourceDocumentJson,
            String actorId) {
        super(id);
        this.ruleCode = ruleCode;
        this.ruleVersion = ruleVersion;
        this.ruleName = ruleName;
        this.ruleScope = ruleScope;
        this.ruleStatus = "ACTIVE";
        this.effectiveFrom = effectiveFrom;
        this.priority = priority;
        this.conditionJson = conditionJson;
        this.outcomeJson = outcomeJson;
        this.sourceDocumentJson = sourceDocumentJson;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.active(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」。
    // 具体功能：「PolicyRuleEntity.active(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」：查询活动状态规则，最终返回「PolicyRuleEntity」。
    // 上游调用：「PolicyRuleEntity.active(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」的上游调用点包括 「RouterApplicationServiceTest.ruleFlowReferencesTheExactPolicyAndVersion」。
    // 下游影响：「PolicyRuleEntity.active(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「PolicyRuleEntity」交给调用方。
    // 系统意义：「PolicyRuleEntity.active(String,String,int,String,String,OffsetDateTime,int,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static PolicyRuleEntity active(
            String id,
            String ruleCode,
            int ruleVersion,
            String ruleName,
            String ruleScope,
            OffsetDateTime effectiveFrom,
            int priority,
            String conditionJson,
            String outcomeJson,
            String sourceDocumentJson,
            String actorId) {
        return new PolicyRuleEntity(
                id,
                ruleCode,
                ruleVersion,
                ruleName,
                ruleScope,
                effectiveFrom,
                priority,
                conditionJson,
                outcomeJson,
                sourceDocumentJson,
                actorId);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.prePersist()」。
    // 具体功能：「PolicyRuleEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「PolicyRuleEntity.prePersist()」由使用「PolicyRuleEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「PolicyRuleEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PolicyRuleEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.preUpdate()」。
    // 具体功能：「PolicyRuleEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「PolicyRuleEntity.preUpdate()」由使用「PolicyRuleEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「PolicyRuleEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PolicyRuleEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getRuleCode()」。
    // 具体功能：「PolicyRuleEntity.getRuleCode()」：读取「PolicyRuleEntity」中的「ruleCode」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PolicyRuleEntity.getRuleCode()」的上游调用点包括 「PolicyApplicationService.toView」、「RuleFlowService.conclude」。
    // 下游影响：「PolicyRuleEntity.getRuleCode()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PolicyRuleEntity.getRuleCode()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRuleCode() {
        return ruleCode;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getRuleVersion()」。
    // 具体功能：「PolicyRuleEntity.getRuleVersion()」：读取「PolicyRuleEntity」中的「ruleVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「PolicyRuleEntity.getRuleVersion()」的上游调用点包括 「PolicyApplicationService.toView」、「RouterApplicationService.createConclusion」、「RuleFlowService.conclude」。
    // 下游影响：「PolicyRuleEntity.getRuleVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「PolicyRuleEntity.getRuleVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getRuleVersion() {
        return ruleVersion;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getRuleName()」。
    // 具体功能：「PolicyRuleEntity.getRuleName()」：读取「PolicyRuleEntity」中的「ruleName」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PolicyRuleEntity.getRuleName()」的上游调用点包括 「PolicyApplicationService.toView」。
    // 下游影响：「PolicyRuleEntity.getRuleName()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PolicyRuleEntity.getRuleName()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRuleName() {
        return ruleName;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getRuleScope()」。
    // 具体功能：「PolicyRuleEntity.getRuleScope()」：读取「PolicyRuleEntity」中的「ruleScope」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PolicyRuleEntity.getRuleScope()」的上游调用点包括 「PolicyApplicationService.toView」。
    // 下游影响：「PolicyRuleEntity.getRuleScope()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PolicyRuleEntity.getRuleScope()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRuleScope() {
        return ruleScope;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getRuleStatus()」。
    // 具体功能：「PolicyRuleEntity.getRuleStatus()」：读取「PolicyRuleEntity」中的「ruleStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PolicyRuleEntity.getRuleStatus()」的上游调用点包括 「PolicyApplicationService.toView」。
    // 下游影响：「PolicyRuleEntity.getRuleStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PolicyRuleEntity.getRuleStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRuleStatus() {
        return ruleStatus;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getEffectiveFrom()」。
    // 具体功能：「PolicyRuleEntity.getEffectiveFrom()」：读取「PolicyRuleEntity」中的「effectiveFrom」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「PolicyRuleEntity.getEffectiveFrom()」的上游调用点包括 「PolicyApplicationService.toView」。
    // 下游影响：「PolicyRuleEntity.getEffectiveFrom()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「PolicyRuleEntity.getEffectiveFrom()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getEffectiveTo()」。
    // 具体功能：「PolicyRuleEntity.getEffectiveTo()」：读取「PolicyRuleEntity」中的「effectiveTo」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「PolicyRuleEntity.getEffectiveTo()」的上游调用点包括 「PolicyApplicationService.toView」。
    // 下游影响：「PolicyRuleEntity.getEffectiveTo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「PolicyRuleEntity.getEffectiveTo()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getEffectiveTo() {
        return effectiveTo;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getPriority()」。
    // 具体功能：「PolicyRuleEntity.getPriority()」：读取「PolicyRuleEntity」中的「priority」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「PolicyRuleEntity.getPriority()」的上游调用点包括 「PolicyApplicationService.toView」。
    // 下游影响：「PolicyRuleEntity.getPriority()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「PolicyRuleEntity.getPriority()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getPriority() {
        return priority;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getConditionJson()」。
    // 具体功能：「PolicyRuleEntity.getConditionJson()」：读取「PolicyRuleEntity」中的「conditionJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PolicyRuleEntity.getConditionJson()」的上游调用点包括 「PolicyApplicationService.toView」。
    // 下游影响：「PolicyRuleEntity.getConditionJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PolicyRuleEntity.getConditionJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getConditionJson() {
        return conditionJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getOutcomeJson()」。
    // 具体功能：「PolicyRuleEntity.getOutcomeJson()」：读取「PolicyRuleEntity」中的「outcomeJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PolicyRuleEntity.getOutcomeJson()」的上游调用点包括 「PolicyApplicationService.toView」、「RuleFlowService.conclude」。
    // 下游影响：「PolicyRuleEntity.getOutcomeJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PolicyRuleEntity.getOutcomeJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getOutcomeJson() {
        return outcomeJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PolicyRuleEntity.getSourceDocumentJson()」。
    // 具体功能：「PolicyRuleEntity.getSourceDocumentJson()」：读取「PolicyRuleEntity」中的「sourceDocumentJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PolicyRuleEntity.getSourceDocumentJson()」的上游调用点包括 「PolicyApplicationService.toView」。
    // 下游影响：「PolicyRuleEntity.getSourceDocumentJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PolicyRuleEntity.getSourceDocumentJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSourceDocumentJson() {
        return sourceDocumentJson;
    }
}
