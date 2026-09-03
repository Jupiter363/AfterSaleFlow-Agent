/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射Adjudication草案数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「prePersist」、「preUpdate」、「getCaseId」、「getDraftVersion」、「getRecommendedDecision」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「AdjudicationDraftEntity」。
// 类型职责：映射Adjudication草案数据库记录并保存可审计状态；本类型显式提供 「AdjudicationDraftEntity」、「AdjudicationDraftEntity」、「create」、「create」、「prePersist」、「preUpdate」。
// 协作关系：主要由 「CaseClosureService.draftSnapshot」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「CaseOutcomeService.adjudicationDraft」、「CaseOutcomeService.finalDecision」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "adjudication_draft")
public class AdjudicationDraftEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "hearing_state_id", length = 64)
    private String hearingStateId;
    @Column(name = "draft_version", nullable = false)
    private int draftVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fact_findings_json", nullable = false, columnDefinition = "jsonb")
    private String factFindingsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_assessment_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceAssessmentJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_application_json", nullable = false, columnDefinition = "jsonb")
    private String policyApplicationJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reviewer_attention_json", nullable = false, columnDefinition = "jsonb")
    private String reviewerAttentionJson;
    @Column(name = "recommended_decision", length = 128, nullable = false)
    private String recommendedDecision;
    @Column(name = "confidence", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence;
    @Column(name = "draft_text", nullable = false, columnDefinition = "text")
    private String draftText;
    @Column(name = "created_by_agent", length = 128, nullable = false)
    private String createdByAgent;
    @Column(name = "created_by_agent_run_id", length = 64)
    private String createdByAgentRunId;
    @Column(name = "draft_status", length = 32, nullable = false)
    private String draftStatus;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.AdjudicationDraftEntity()」。
    // 具体功能：「AdjudicationDraftEntity.AdjudicationDraftEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AdjudicationDraftEntity.AdjudicationDraftEntity()」的上游创建点包括 「AdjudicationDraftEntity.create」。
    // 下游影响：「AdjudicationDraftEntity.AdjudicationDraftEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AdjudicationDraftEntity.AdjudicationDraftEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected AdjudicationDraftEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.AdjudicationDraftEntity(String)」。
    // 具体功能：「AdjudicationDraftEntity.AdjudicationDraftEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AdjudicationDraftEntity.AdjudicationDraftEntity(String)」的上游创建点包括 「AdjudicationDraftEntity.create」。
    // 下游影响：「AdjudicationDraftEntity.AdjudicationDraftEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AdjudicationDraftEntity.AdjudicationDraftEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private AdjudicationDraftEntity(String id) { super(id); }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String)」。
    // 具体功能：「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String)」：提供「create」的便捷重载：接收 「id」(String)、「caseId」(String)、「hearingStateId」(String)、「version」(int)、「factFindingsJson」(String)、「evidenceAssessmentJson」(String)、「policyApplicationJson」(String)、「reviewerAttentionJson」(String)、「recommendedDecision」(String)、「confidence」(BigDecimal)、「draftText」(String)、「agent」(String)、「draftStatus」(String)、「actorId」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String)」的上游调用点包括 「AdjudicationDraftEntity.create」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「FinalWorkflowActivitiesAdapter.ensureSettlementDraft」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」。
    // 下游影响：「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String)」向下依次触达 「create」；计算结果以「AdjudicationDraftEntity」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static AdjudicationDraftEntity create(
            String id, String caseId, String hearingStateId, int version,
            String factFindingsJson, String evidenceAssessmentJson,
            String policyApplicationJson, String reviewerAttentionJson,
            String recommendedDecision, BigDecimal confidence, String draftText,
            String agent, String draftStatus, String actorId) {
        return create(
                id,
                caseId,
                hearingStateId,
                version,
                factFindingsJson,
                evidenceAssessmentJson,
                policyApplicationJson,
                reviewerAttentionJson,
                recommendedDecision,
                confidence,
                draftText,
                agent,
                null,
                draftStatus,
                actorId);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String,String)」。
    // 具体功能：「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String,String)」：创建Adjudication草案：先更新内部状态 「caseId」、「hearingStateId」、「draftVersion」、「factFindingsJson」，最终返回「AdjudicationDraftEntity」。
    // 上游调用：「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String,String)」的上游调用点包括 「AdjudicationDraftEntity.create」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「FinalWorkflowActivitiesAdapter.ensureSettlementDraft」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」。
    // 下游影响：「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「AdjudicationDraftEntity」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.create(String,String,String,int,String,String,String,String,String,BigDecimal,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static AdjudicationDraftEntity create(
            String id, String caseId, String hearingStateId, int version,
            String factFindingsJson, String evidenceAssessmentJson,
            String policyApplicationJson, String reviewerAttentionJson,
            String recommendedDecision, BigDecimal confidence, String draftText,
            String agent, String agentRunId, String draftStatus, String actorId) {
        AdjudicationDraftEntity draft = new AdjudicationDraftEntity(id);
        draft.caseId = caseId;
        draft.hearingStateId = hearingStateId;
        draft.draftVersion = version;
        draft.factFindingsJson = factFindingsJson;
        draft.evidenceAssessmentJson = evidenceAssessmentJson;
        draft.policyApplicationJson = policyApplicationJson;
        draft.reviewerAttentionJson = reviewerAttentionJson;
        draft.recommendedDecision = recommendedDecision;
        draft.confidence = confidence;
        draft.draftText = draftText;
        draft.createdByAgent = agent;
        draft.createdByAgentRunId = agentRunId;
        draft.draftStatus = draftStatus;
        draft.createdBy = actorId;
        draft.updatedBy = actorId;
        return draft;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.prePersist()」。
    // 具体功能：「AdjudicationDraftEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「AdjudicationDraftEntity.prePersist()」由使用「AdjudicationDraftEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AdjudicationDraftEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AdjudicationDraftEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.preUpdate()」。
    // 具体功能：「AdjudicationDraftEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「AdjudicationDraftEntity.preUpdate()」由使用「AdjudicationDraftEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AdjudicationDraftEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AdjudicationDraftEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate void preUpdate() { updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getCaseId()」。
    // 具体功能：「AdjudicationDraftEntity.getCaseId()」：读取「AdjudicationDraftEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AdjudicationDraftEntity.getCaseId()」由使用「AdjudicationDraftEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AdjudicationDraftEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() { return caseId; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getDraftVersion()」。
    // 具体功能：「AdjudicationDraftEntity.getDraftVersion()」：读取「AdjudicationDraftEntity」中的「draftVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「AdjudicationDraftEntity.getDraftVersion()」的上游调用点包括 「CaseClosureService.draftSnapshot」、「CaseOutcomeService.adjudicationDraft」。
    // 下游影响：「AdjudicationDraftEntity.getDraftVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getDraftVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getDraftVersion() { return draftVersion; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getRecommendedDecision()」。
    // 具体功能：「AdjudicationDraftEntity.getRecommendedDecision()」：读取「AdjudicationDraftEntity」中的「recommendedDecision」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AdjudicationDraftEntity.getRecommendedDecision()」的上游调用点包括 「CaseClosureService.draftSnapshot」、「CaseOutcomeService.adjudicationDraft」、「CaseOutcomeService.finalDecision」。
    // 下游影响：「AdjudicationDraftEntity.getRecommendedDecision()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getRecommendedDecision()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRecommendedDecision() { return recommendedDecision; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getConfidence()」。
    // 具体功能：「AdjudicationDraftEntity.getConfidence()」：读取「AdjudicationDraftEntity」中的「confidence」状态，向 JPA、应用服务或序列化层返回「BigDecimal」。
    // 上游调用：「AdjudicationDraftEntity.getConfidence()」的上游调用点包括 「CaseClosureService.draftSnapshot」、「HearingFinalDraftService.completeStateIfNeeded」、「CaseOutcomeService.adjudicationDraft」。
    // 下游影响：「AdjudicationDraftEntity.getConfidence()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「BigDecimal」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getConfidence()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public BigDecimal getConfidence() { return confidence; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getDraftText()」。
    // 具体功能：「AdjudicationDraftEntity.getDraftText()」：读取「AdjudicationDraftEntity」中的「draftText」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AdjudicationDraftEntity.getDraftText()」的上游调用点包括 「CaseOutcomeService.adjudicationDraft」、「CaseOutcomeService.finalDecision」。
    // 下游影响：「AdjudicationDraftEntity.getDraftText()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getDraftText()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getDraftText() { return draftText; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getDraftStatus()」。
    // 具体功能：「AdjudicationDraftEntity.getDraftStatus()」：读取「AdjudicationDraftEntity」中的「draftStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AdjudicationDraftEntity.getDraftStatus()」的上游调用点包括 「CaseClosureService.draftSnapshot」、「CaseOutcomeService.adjudicationDraft」。
    // 下游影响：「AdjudicationDraftEntity.getDraftStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getDraftStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getDraftStatus() { return draftStatus; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getFactFindingsJson()」。
    // 具体功能：「AdjudicationDraftEntity.getFactFindingsJson()」：读取「AdjudicationDraftEntity」中的「factFindingsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AdjudicationDraftEntity.getFactFindingsJson()」的上游调用点包括 「CaseClosureService.draftSnapshot」、「CaseOutcomeService.adjudicationDraft」。
    // 下游影响：「AdjudicationDraftEntity.getFactFindingsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getFactFindingsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getFactFindingsJson() { return factFindingsJson; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getEvidenceAssessmentJson()」。
    // 具体功能：「AdjudicationDraftEntity.getEvidenceAssessmentJson()」：读取「AdjudicationDraftEntity」中的「evidenceAssessmentJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AdjudicationDraftEntity.getEvidenceAssessmentJson()」的上游调用点包括 「CaseClosureService.draftSnapshot」、「CaseOutcomeService.adjudicationDraft」。
    // 下游影响：「AdjudicationDraftEntity.getEvidenceAssessmentJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getEvidenceAssessmentJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getEvidenceAssessmentJson() { return evidenceAssessmentJson; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getPolicyApplicationJson()」。
    // 具体功能：「AdjudicationDraftEntity.getPolicyApplicationJson()」：读取「AdjudicationDraftEntity」中的「policyApplicationJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AdjudicationDraftEntity.getPolicyApplicationJson()」的上游调用点包括 「CaseClosureService.draftSnapshot」、「CaseOutcomeService.adjudicationDraft」。
    // 下游影响：「AdjudicationDraftEntity.getPolicyApplicationJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getPolicyApplicationJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPolicyApplicationJson() { return policyApplicationJson; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getReviewerAttentionJson()」。
    // 具体功能：「AdjudicationDraftEntity.getReviewerAttentionJson()」：读取「AdjudicationDraftEntity」中的「reviewerAttentionJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AdjudicationDraftEntity.getReviewerAttentionJson()」的上游调用点包括 「CaseOutcomeService.adjudicationDraft」。
    // 下游影响：「AdjudicationDraftEntity.getReviewerAttentionJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getReviewerAttentionJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getReviewerAttentionJson() { return reviewerAttentionJson; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AdjudicationDraftEntity.getCreatedByAgentRunId()」。
    // 具体功能：「AdjudicationDraftEntity.getCreatedByAgentRunId()」：读取「AdjudicationDraftEntity」中的「createdByAgentRunId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AdjudicationDraftEntity.getCreatedByAgentRunId()」由使用「AdjudicationDraftEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AdjudicationDraftEntity.getCreatedByAgentRunId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AdjudicationDraftEntity.getCreatedByAgentRunId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCreatedByAgentRunId() { return createdByAgentRunId; }
}
