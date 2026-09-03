/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射审核审核包数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「createFrozen」、「prePersist」、「preUpdate」、「getCaseId」、「getPlanId」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.review.domain.ReviewPacketVersions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「ReviewPacketEntity」。
// 类型职责：映射审核审核包数据库记录并保存可审计状态；本类型显式提供 「ReviewPacketEntity」、「ReviewPacketEntity」、「create」、「createFrozen」、「createFrozen」、「prePersist」。
// 协作关系：主要由 「ReviewApplicationService.createForWorkflow」、「CaseClosureServiceIntegrationTest.seed」、「FrozenReviewPacketTest.freezesEverySourceVersionAndActionHashBeforeReview」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "review_packet")
public class ReviewPacketEntity extends AbstractEntity {
    @Column(name = "case_id", length = 64, nullable = false) private String caseId;
    @Column(name = "plan_id", length = 64, nullable = false) private String planId;
    @Column(name = "packet_version", nullable = false) private int packetVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "case_summary_json", nullable = false, columnDefinition = "jsonb") private String caseSummaryJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "claims_json", nullable = false, columnDefinition = "jsonb") private String claimsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "issues_json", nullable = false, columnDefinition = "jsonb") private String issuesJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_matrix_json", nullable = false, columnDefinition = "jsonb") private String evidenceMatrixJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "draft_json", nullable = false, columnDefinition = "jsonb") private String draftJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "remedy_json", nullable = false, columnDefinition = "jsonb") private String remedyJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "risk_flags_json", nullable = false, columnDefinition = "jsonb") private String riskFlagsJson;
    @Column(name = "packet_status", length = 32, nullable = false) private String packetStatus;
    @Column(name = "case_version", nullable = false) private long caseVersion;
    @Column(name = "dossier_version", nullable = false) private int dossierVersion;
    @Column(name = "issue_version", nullable = false) private int issueVersion;
    @Column(name = "adjudication_draft_version", nullable = false) private int adjudicationDraftVersion;
    @Column(name = "deliberation_report_version", nullable = false) private int deliberationReportVersion;
    @Column(name = "remedy_plan_version", nullable = false) private int remedyPlanVersion;
    @Column(name = "ruleset_version", length = 64, nullable = false) private String rulesetVersion;
    @Column(name = "prompt_version", length = 64, nullable = false) private String promptVersion;
    @Column(name = "skill_version", length = 64, nullable = false) private String skillVersion;
    @Column(name = "profile_version", length = 64, nullable = false) private String profileVersion;
    @Column(name = "action_hash", length = 128, nullable = false) private String actionHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "agent_run_refs_json", nullable = false, columnDefinition = "jsonb") private String agentRunRefsJson;
    @Column(name = "frozen", nullable = false) private boolean frozen;
    @Column(name = "frozen_at", nullable = false) private OffsetDateTime frozenAt;
    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false) private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false) private String updatedBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.ReviewPacketEntity()」。
    // 具体功能：「ReviewPacketEntity.ReviewPacketEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ReviewPacketEntity.ReviewPacketEntity()」的上游创建点包括 「ReviewPacketEntity.createFrozen」。
    // 下游影响：「ReviewPacketEntity.ReviewPacketEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewPacketEntity.ReviewPacketEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected ReviewPacketEntity() {}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.ReviewPacketEntity(String)」。
    // 具体功能：「ReviewPacketEntity.ReviewPacketEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ReviewPacketEntity.ReviewPacketEntity(String)」的上游创建点包括 「ReviewPacketEntity.createFrozen」。
    // 下游影响：「ReviewPacketEntity.ReviewPacketEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewPacketEntity.ReviewPacketEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private ReviewPacketEntity(String id) { super(id); }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.create(String,String,String,int,String,String,String,String,String,String,String,String)」。
    // 具体功能：「ReviewPacketEntity.create(String,String,String,int,String,String,String,String,String,String,String,String)」：创建审核审核包；实际协作者为 「now.plusDays」、「createFrozen」；处理的关键状态/协议值包括 「legacy-ruleset」、「legacy-prompt」、「legacy-skill」、「legacy-profile」，最终返回「ReviewPacketEntity」。
    // 上游调用：「ReviewPacketEntity.create(String,String,String,int,String,String,String,String,String,String,String,String)」的上游调用点包括 「CaseClosureServiceIntegrationTest.seed」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」。
    // 下游影响：「ReviewPacketEntity.create(String,String,String,int,String,String,String,String,String,String,String,String)」向下依次触达 「now.plusDays」、「createFrozen」；计算结果以「ReviewPacketEntity」交给调用方。
    // 系统意义：「ReviewPacketEntity.create(String,String,String,int,String,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ReviewPacketEntity create(
            String id, String caseId, String planId, int version,
            String caseSummaryJson, String claimsJson, String issuesJson,
            String evidenceMatrixJson, String draftJson, String remedyJson,
            String riskFlagsJson, String actorId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return createFrozen(
                id,
                caseId,
                planId,
                version,
                new ReviewPacketVersions(
                        1,
                        1,
                        1,
                        1,
                        0,
                        1,
                        "legacy-ruleset",
                        "legacy-prompt",
                        "legacy-skill",
                        "legacy-profile"),
                "LEGACY_" + id,
                now,
                now.plusDays(7),
                caseSummaryJson,
                claimsJson,
                issuesJson,
                evidenceMatrixJson,
                draftJson,
                remedyJson,
                riskFlagsJson,
                actorId);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String)」。
    // 具体功能：「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String)」：提供「createFrozen」的便捷重载：接收 「id」(String)、「caseId」(String)、「planId」(String)、「version」(int)、「versions」(ReviewPacketVersions)、「actionHash」(String)、「frozenAt」(OffsetDateTime)、「expiresAt」(OffsetDateTime)、「caseSummaryJson」(String)、「claimsJson」(String)、「issuesJson」(String)、「evidenceMatrixJson」(String)、「draftJson」(String)、「remedyJson」(String)、「riskFlagsJson」(String)、「actorId」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String)」的上游调用点包括 「ReviewPacketEntity.create」、「ReviewPacketEntity.createFrozen」、「ReviewApplicationService.createForWorkflow」、「ToolExecutorServiceIntegrationTest.rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals」。
    // 下游影响：「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String)」向下依次触达 「createFrozen」；计算结果以「ReviewPacketEntity」交给调用方。
    // 系统意义：「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ReviewPacketEntity createFrozen(
            String id,
            String caseId,
            String planId,
            int version,
            ReviewPacketVersions versions,
            String actionHash,
            OffsetDateTime frozenAt,
            OffsetDateTime expiresAt,
            String caseSummaryJson,
            String claimsJson,
            String issuesJson,
            String evidenceMatrixJson,
            String draftJson,
            String remedyJson,
            String riskFlagsJson,
            String actorId) {
        return createFrozen(
                id,
                caseId,
                planId,
                version,
                versions,
                actionHash,
                frozenAt,
                expiresAt,
                "[]",
                caseSummaryJson,
                claimsJson,
                issuesJson,
                evidenceMatrixJson,
                draftJson,
                remedyJson,
                riskFlagsJson,
                actorId);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String,String)」。
    // 具体功能：「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String,String)」：创建冻结：先更新内部状态 「caseId」、「planId」、「packetVersion」、「caseSummaryJson」；实际协作者为 「versions.caseVersion」、「versions.dossierVersion」、「versions.issueVersion」、「versions.adjudicationDraftVersion」；处理的关键状态/协议值包括 「FROZEN」，最终返回「ReviewPacketEntity」。
    // 上游调用：「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String,String)」的上游调用点包括 「ReviewPacketEntity.create」、「ReviewPacketEntity.createFrozen」、「ReviewApplicationService.createForWorkflow」、「ToolExecutorServiceIntegrationTest.rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals」。
    // 下游影响：「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String,String)」向下依次触达 「versions.caseVersion」、「versions.dossierVersion」、「versions.issueVersion」、「versions.adjudicationDraftVersion」；计算结果以「ReviewPacketEntity」交给调用方。
    // 系统意义：「ReviewPacketEntity.createFrozen(String,String,String,int,ReviewPacketVersions,String,OffsetDateTime,OffsetDateTime,String,String,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ReviewPacketEntity createFrozen(
            String id,
            String caseId,
            String planId,
            int version,
            ReviewPacketVersions versions,
            String actionHash,
            OffsetDateTime frozenAt,
            OffsetDateTime expiresAt,
            String agentRunRefsJson,
            String caseSummaryJson,
            String claimsJson,
            String issuesJson,
            String evidenceMatrixJson,
            String draftJson,
            String remedyJson,
            String riskFlagsJson,
            String actorId) {
        ReviewPacketEntity packet = new ReviewPacketEntity(id);
        packet.caseId=caseId; packet.planId=planId; packet.packetVersion=version;
        packet.caseSummaryJson=caseSummaryJson; packet.claimsJson=claimsJson;
        packet.issuesJson=issuesJson; packet.evidenceMatrixJson=evidenceMatrixJson;
        packet.draftJson=draftJson; packet.remedyJson=remedyJson;
        packet.riskFlagsJson=riskFlagsJson; packet.packetStatus="FROZEN";
        packet.caseVersion=versions.caseVersion();
        packet.dossierVersion=versions.dossierVersion();
        packet.issueVersion=versions.issueVersion();
        packet.adjudicationDraftVersion=versions.adjudicationDraftVersion();
        packet.deliberationReportVersion=versions.deliberationReportVersion();
        packet.remedyPlanVersion=versions.remedyPlanVersion();
        packet.rulesetVersion=versions.rulesetVersion();
        packet.promptVersion=versions.promptVersion();
        packet.skillVersion=versions.skillVersion();
        packet.profileVersion=versions.profileVersion();
        packet.actionHash=actionHash;
        packet.agentRunRefsJson=agentRunRefsJson;
        packet.frozen=true;
        packet.frozenAt=frozenAt;
        packet.expiresAt=expiresAt;
        packet.createdBy=actorId; packet.updatedBy=actorId; return packet;
    }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.prePersist()」。
    // 具体功能：「ReviewPacketEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「ReviewPacketEntity.prePersist()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewPacketEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist void prePersist(){createdAt=OffsetDateTime.now(ZoneOffset.UTC);updatedAt=createdAt;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.preUpdate()」。
    // 具体功能：「ReviewPacketEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「ReviewPacketEntity.preUpdate()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewPacketEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate
    void preUpdate(){
        // A reviewer must always see the exact snapshot later executed.
        if (frozen) {
            throw new IllegalStateException("frozen review packet cannot be mutated");
        }
        updatedAt=OffsetDateTime.now(ZoneOffset.UTC);
    }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getCaseId()」。
    // 具体功能：「ReviewPacketEntity.getCaseId()」：读取「ReviewPacketEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getCaseId()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId(){return caseId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getPlanId()」。
    // 具体功能：「ReviewPacketEntity.getPlanId()」：读取「ReviewPacketEntity」中的「planId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getPlanId()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getPlanId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getPlanId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPlanId(){return planId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getPacketVersion()」。
    // 具体功能：「ReviewPacketEntity.getPacketVersion()」：读取「ReviewPacketEntity」中的「packetVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「ReviewPacketEntity.getPacketVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getPacketVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「ReviewPacketEntity.getPacketVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getPacketVersion(){return packetVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getCaseSummaryJson()」。
    // 具体功能：「ReviewPacketEntity.getCaseSummaryJson()」：读取「ReviewPacketEntity」中的「caseSummaryJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getCaseSummaryJson()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getCaseSummaryJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getCaseSummaryJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseSummaryJson(){return caseSummaryJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getClaimsJson()」。
    // 具体功能：「ReviewPacketEntity.getClaimsJson()」：读取「ReviewPacketEntity」中的「claimsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getClaimsJson()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getClaimsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getClaimsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getClaimsJson(){return claimsJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getIssuesJson()」。
    // 具体功能：「ReviewPacketEntity.getIssuesJson()」：读取「ReviewPacketEntity」中的「issuesJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getIssuesJson()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getIssuesJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getIssuesJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getIssuesJson(){return issuesJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getEvidenceMatrixJson()」。
    // 具体功能：「ReviewPacketEntity.getEvidenceMatrixJson()」：读取「ReviewPacketEntity」中的「evidenceMatrixJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getEvidenceMatrixJson()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getEvidenceMatrixJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getEvidenceMatrixJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getEvidenceMatrixJson(){return evidenceMatrixJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getDraftJson()」。
    // 具体功能：「ReviewPacketEntity.getDraftJson()」：读取「ReviewPacketEntity」中的「draftJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getDraftJson()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getDraftJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getDraftJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getDraftJson(){return draftJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getRemedyJson()」。
    // 具体功能：「ReviewPacketEntity.getRemedyJson()」：读取「ReviewPacketEntity」中的「remedyJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getRemedyJson()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getRemedyJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getRemedyJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRemedyJson(){return remedyJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getRiskFlagsJson()」。
    // 具体功能：「ReviewPacketEntity.getRiskFlagsJson()」：读取「ReviewPacketEntity」中的「riskFlagsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getRiskFlagsJson()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getRiskFlagsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getRiskFlagsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRiskFlagsJson(){return riskFlagsJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getPacketStatus()」。
    // 具体功能：「ReviewPacketEntity.getPacketStatus()」：读取「ReviewPacketEntity」中的「packetStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getPacketStatus()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getPacketStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getPacketStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPacketStatus(){return packetStatus;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getCaseVersion()」。
    // 具体功能：「ReviewPacketEntity.getCaseVersion()」：读取「ReviewPacketEntity」中的「caseVersion」状态，向 JPA、应用服务或序列化层返回「long」。
    // 上游调用：「ReviewPacketEntity.getCaseVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getCaseVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「long」交给调用方。
    // 系统意义：「ReviewPacketEntity.getCaseVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public long getCaseVersion(){return caseVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getDossierVersion()」。
    // 具体功能：「ReviewPacketEntity.getDossierVersion()」：读取「ReviewPacketEntity」中的「dossierVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「ReviewPacketEntity.getDossierVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getDossierVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「ReviewPacketEntity.getDossierVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getDossierVersion(){return dossierVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getIssueVersion()」。
    // 具体功能：「ReviewPacketEntity.getIssueVersion()」：读取「ReviewPacketEntity」中的「issueVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「ReviewPacketEntity.getIssueVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getIssueVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「ReviewPacketEntity.getIssueVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getIssueVersion(){return issueVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getAdjudicationDraftVersion()」。
    // 具体功能：「ReviewPacketEntity.getAdjudicationDraftVersion()」：读取「ReviewPacketEntity」中的「adjudicationDraftVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「ReviewPacketEntity.getAdjudicationDraftVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getAdjudicationDraftVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「ReviewPacketEntity.getAdjudicationDraftVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getAdjudicationDraftVersion(){return adjudicationDraftVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getDeliberationReportVersion()」。
    // 具体功能：「ReviewPacketEntity.getDeliberationReportVersion()」：读取「ReviewPacketEntity」中的「deliberationReportVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「ReviewPacketEntity.getDeliberationReportVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getDeliberationReportVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「ReviewPacketEntity.getDeliberationReportVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getDeliberationReportVersion(){return deliberationReportVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getRemedyPlanVersion()」。
    // 具体功能：「ReviewPacketEntity.getRemedyPlanVersion()」：读取「ReviewPacketEntity」中的「remedyPlanVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「ReviewPacketEntity.getRemedyPlanVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getRemedyPlanVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「ReviewPacketEntity.getRemedyPlanVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getRemedyPlanVersion(){return remedyPlanVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getRulesetVersion()」。
    // 具体功能：「ReviewPacketEntity.getRulesetVersion()」：读取「ReviewPacketEntity」中的「rulesetVersion」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getRulesetVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getRulesetVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getRulesetVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRulesetVersion(){return rulesetVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getPromptVersion()」。
    // 具体功能：「ReviewPacketEntity.getPromptVersion()」：读取「ReviewPacketEntity」中的「promptVersion」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getPromptVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getPromptVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getPromptVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPromptVersion(){return promptVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getSkillVersion()」。
    // 具体功能：「ReviewPacketEntity.getSkillVersion()」：读取「ReviewPacketEntity」中的「skillVersion」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getSkillVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getSkillVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getSkillVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSkillVersion(){return skillVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getProfileVersion()」。
    // 具体功能：「ReviewPacketEntity.getProfileVersion()」：读取「ReviewPacketEntity」中的「profileVersion」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getProfileVersion()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getProfileVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getProfileVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getProfileVersion(){return profileVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getActionHash()」。
    // 具体功能：「ReviewPacketEntity.getActionHash()」：读取「ReviewPacketEntity」中的「actionHash」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getActionHash()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getActionHash()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getActionHash()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getActionHash(){return actionHash;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getAgentRunRefsJson()」。
    // 具体功能：「ReviewPacketEntity.getAgentRunRefsJson()」：读取「ReviewPacketEntity」中的「agentRunRefsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ReviewPacketEntity.getAgentRunRefsJson()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getAgentRunRefsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ReviewPacketEntity.getAgentRunRefsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getAgentRunRefsJson(){return agentRunRefsJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.isFrozen()」。
    // 具体功能：「ReviewPacketEntity.isFrozen()」：判断是否冻结，最终返回「boolean」。
    // 上游调用：「ReviewPacketEntity.isFrozen()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.isFrozen()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「ReviewPacketEntity.isFrozen()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public boolean isFrozen(){return frozen;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getFrozenAt()」。
    // 具体功能：「ReviewPacketEntity.getFrozenAt()」：读取「ReviewPacketEntity」中的「frozenAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「ReviewPacketEntity.getFrozenAt()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getFrozenAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「ReviewPacketEntity.getFrozenAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getFrozenAt(){return frozenAt;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ReviewPacketEntity.getExpiresAt()」。
    // 具体功能：「ReviewPacketEntity.getExpiresAt()」：读取「ReviewPacketEntity」中的「expiresAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「ReviewPacketEntity.getExpiresAt()」由使用「ReviewPacketEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ReviewPacketEntity.getExpiresAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「ReviewPacketEntity.getExpiresAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getExpiresAt(){return expiresAt;}
}
