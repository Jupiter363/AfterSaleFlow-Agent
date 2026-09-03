/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射补救方案数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「pendingApproval」、「prePersist」、「preUpdate」、「getCaseId」、「getAdjudicationDraftId」、「getPlanVersion」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「RemedyPlanEntity」。
// 类型职责：映射补救方案数据库记录并保存可审计状态；本类型显式提供 「RemedyPlanEntity」、「RemedyPlanEntity」、「pendingApproval」、「prePersist」、「preUpdate」、「getCaseId」。
// 协作关系：主要由 「CaseOutcomeService.approvedPlan」、「RemedyApplicationService.generate」、「RemedyApplicationService.toView」、「ToolExecutorService.approvedActions」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "remedy_plan")
public class RemedyPlanEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "adjudication_draft_id", length = 64)
    private String adjudicationDraftId;

    @Column(name = "plan_version", nullable = false)
    private int planVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_route", length = 64, nullable = false)
    private RouteType sourceRoute;

    @Column(name = "plan_status", length = 32, nullable = false)
    private String planStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions_json", nullable = false, columnDefinition = "jsonb")
    private String actionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preconditions_json", nullable = false, columnDefinition = "jsonb")
    private String preconditionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "notification_plan_json",
            nullable = false,
            columnDefinition = "jsonb")
    private String notificationPlanJson;

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

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.RemedyPlanEntity()」。
    // 具体功能：「RemedyPlanEntity.RemedyPlanEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「RemedyPlanEntity.RemedyPlanEntity()」的上游创建点包括 「RemedyPlanEntity.pendingApproval」。
    // 下游影响：「RemedyPlanEntity.RemedyPlanEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RemedyPlanEntity.RemedyPlanEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected RemedyPlanEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.RemedyPlanEntity(String)」。
    // 具体功能：「RemedyPlanEntity.RemedyPlanEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「RemedyPlanEntity.RemedyPlanEntity(String)」的上游创建点包括 「RemedyPlanEntity.pendingApproval」。
    // 下游影响：「RemedyPlanEntity.RemedyPlanEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RemedyPlanEntity.RemedyPlanEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private RemedyPlanEntity(String id) {
        super(id);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.pendingApproval(String,String,String,int,RouteType,RiskLevel,String,String,String,String)」。
    // 具体功能：「RemedyPlanEntity.pendingApproval(String,String,String,int,RouteType,RiskLevel,String,String,String,String)」：更新待处理审批：先更新内部状态 「caseId」、「adjudicationDraftId」、「planVersion」、「sourceRoute」；实际协作者为 「BigDecimal.ZERO.setScale」；处理的关键状态/协议值包括 「PENDING_APPROVAL」、「CNY」，最终返回「RemedyPlanEntity」。
    // 上游调用：「RemedyPlanEntity.pendingApproval(String,String,String,int,RouteType,RiskLevel,String,String,String,String)」的上游调用点包括 「RemedyApplicationService.generate」、「CaseClosureServiceIntegrationTest.seed」、「ToolExecutorServiceIntegrationTest.seed」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」。
    // 下游影响：「RemedyPlanEntity.pendingApproval(String,String,String,int,RouteType,RiskLevel,String,String,String,String)」向下依次触达 「BigDecimal.ZERO.setScale」；计算结果以「RemedyPlanEntity」交给调用方。
    // 系统意义：「RemedyPlanEntity.pendingApproval(String,String,String,int,RouteType,RiskLevel,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static RemedyPlanEntity pendingApproval(
            String id,
            String caseId,
            String adjudicationDraftId,
            int planVersion,
            RouteType sourceRoute,
            RiskLevel riskLevel,
            String actionsJson,
            String preconditionsJson,
            String notificationPlanJson,
            String actorId) {
        RemedyPlanEntity plan = new RemedyPlanEntity(id);
        plan.caseId = caseId;
        plan.adjudicationDraftId = adjudicationDraftId;
        plan.planVersion = planVersion;
        plan.sourceRoute = sourceRoute;
        plan.planStatus = "PENDING_APPROVAL";
        plan.riskLevel = riskLevel;
        plan.totalAmount = BigDecimal.ZERO.setScale(2);
        plan.currency = "CNY";
        plan.actionsJson = actionsJson;
        plan.preconditionsJson = preconditionsJson;
        plan.notificationPlanJson = notificationPlanJson;
        plan.requiresHumanReview = true;
        plan.createdBy = actorId;
        plan.updatedBy = actorId;
        return plan;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.prePersist()」。
    // 具体功能：「RemedyPlanEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「RemedyPlanEntity.prePersist()」由使用「RemedyPlanEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RemedyPlanEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RemedyPlanEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.preUpdate()」。
    // 具体功能：「RemedyPlanEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「RemedyPlanEntity.preUpdate()」由使用「RemedyPlanEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RemedyPlanEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RemedyPlanEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getCaseId()」。
    // 具体功能：「RemedyPlanEntity.getCaseId()」：读取「RemedyPlanEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RemedyPlanEntity.getCaseId()」的上游调用点包括 「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RemedyPlanEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() { return caseId; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getAdjudicationDraftId()」。
    // 具体功能：「RemedyPlanEntity.getAdjudicationDraftId()」：读取「RemedyPlanEntity」中的「adjudicationDraftId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RemedyPlanEntity.getAdjudicationDraftId()」的上游调用点包括 「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getAdjudicationDraftId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RemedyPlanEntity.getAdjudicationDraftId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getAdjudicationDraftId() { return adjudicationDraftId; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getPlanVersion()」。
    // 具体功能：「RemedyPlanEntity.getPlanVersion()」：读取「RemedyPlanEntity」中的「planVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「RemedyPlanEntity.getPlanVersion()」的上游调用点包括 「ToolExecutorService.approvedActions」、「CaseOutcomeService.approvedPlan」、「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getPlanVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「RemedyPlanEntity.getPlanVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getPlanVersion() { return planVersion; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getSourceRoute()」。
    // 具体功能：「RemedyPlanEntity.getSourceRoute()」：读取「RemedyPlanEntity」中的「sourceRoute」状态，向 JPA、应用服务或序列化层返回「RouteType」。
    // 上游调用：「RemedyPlanEntity.getSourceRoute()」的上游调用点包括 「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getSourceRoute()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RouteType」交给调用方。
    // 系统意义：「RemedyPlanEntity.getSourceRoute()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public RouteType getSourceRoute() { return sourceRoute; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getPlanStatus()」。
    // 具体功能：「RemedyPlanEntity.getPlanStatus()」：读取「RemedyPlanEntity」中的「planStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RemedyPlanEntity.getPlanStatus()」的上游调用点包括 「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getPlanStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RemedyPlanEntity.getPlanStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPlanStatus() { return planStatus; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getRiskLevel()」。
    // 具体功能：「RemedyPlanEntity.getRiskLevel()」：读取「RemedyPlanEntity」中的「riskLevel」状态，向 JPA、应用服务或序列化层返回「RiskLevel」。
    // 上游调用：「RemedyPlanEntity.getRiskLevel()」的上游调用点包括 「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getRiskLevel()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RiskLevel」交给调用方。
    // 系统意义：「RemedyPlanEntity.getRiskLevel()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public RiskLevel getRiskLevel() { return riskLevel; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getTotalAmount()」。
    // 具体功能：「RemedyPlanEntity.getTotalAmount()」：读取「RemedyPlanEntity」中的「totalAmount」状态，向 JPA、应用服务或序列化层返回「BigDecimal」。
    // 上游调用：「RemedyPlanEntity.getTotalAmount()」的上游调用点包括 「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getTotalAmount()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「BigDecimal」交给调用方。
    // 系统意义：「RemedyPlanEntity.getTotalAmount()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public BigDecimal getTotalAmount() { return totalAmount; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getCurrency()」。
    // 具体功能：「RemedyPlanEntity.getCurrency()」：读取「RemedyPlanEntity」中的「currency」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RemedyPlanEntity.getCurrency()」的上游调用点包括 「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getCurrency()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RemedyPlanEntity.getCurrency()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCurrency() { return currency; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getActionsJson()」。
    // 具体功能：「RemedyPlanEntity.getActionsJson()」：读取「RemedyPlanEntity」中的「actionsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RemedyPlanEntity.getActionsJson()」的上游调用点包括 「ToolExecutorService.approvedActions」、「CaseOutcomeService.approvedPlan」、「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getActionsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RemedyPlanEntity.getActionsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getActionsJson() { return actionsJson; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getPreconditionsJson()」。
    // 具体功能：「RemedyPlanEntity.getPreconditionsJson()」：读取「RemedyPlanEntity」中的「preconditionsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RemedyPlanEntity.getPreconditionsJson()」的上游调用点包括 「CaseOutcomeService.approvedPlan」、「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getPreconditionsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RemedyPlanEntity.getPreconditionsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPreconditionsJson() { return preconditionsJson; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getNotificationPlanJson()」。
    // 具体功能：「RemedyPlanEntity.getNotificationPlanJson()」：读取「RemedyPlanEntity」中的「notificationPlanJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RemedyPlanEntity.getNotificationPlanJson()」的上游调用点包括 「ToolExecutorService.approvedActions」、「CaseOutcomeService.approvedPlan」、「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getNotificationPlanJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RemedyPlanEntity.getNotificationPlanJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getNotificationPlanJson() { return notificationPlanJson; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.isRequiresHumanReview()」。
    // 具体功能：「RemedyPlanEntity.isRequiresHumanReview()」：判断是否Requires人工审核，最终返回「boolean」。
    // 上游调用：「RemedyPlanEntity.isRequiresHumanReview()」的上游调用点包括 「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.isRequiresHumanReview()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「RemedyPlanEntity.isRequiresHumanReview()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public boolean isRequiresHumanReview() { return requiresHumanReview; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RemedyPlanEntity.getCreatedAt()」。
    // 具体功能：「RemedyPlanEntity.getCreatedAt()」：读取「RemedyPlanEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「RemedyPlanEntity.getCreatedAt()」的上游调用点包括 「RemedyApplicationService.toView」。
    // 下游影响：「RemedyPlanEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「RemedyPlanEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
