/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射审批记录数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「record」、「recordFrozen」、「prePersist」、「getCaseId」、「getReviewTaskId」、「getPlanId」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.ApprovalDecisionType;
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

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「ApprovalRecordEntity」。
// 类型职责：映射审批记录数据库记录并保存可审计状态；本类型显式提供 「ApprovalRecordEntity」、「ApprovalRecordEntity」、「record」、「recordFrozen」、「prePersist」、「getCaseId」。
// 协作关系：主要由 「CaseClosureService.buildSnapshot」、「CaseClosureService.validateCompletedExecution」、「CaseOutcomeService.finalDecision」、「PostReviewOrchestrationService.audit」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "human_review_record")
public class ApprovalRecordEntity extends AbstractEntity {
    @Column(name="case_id",length=64,nullable=false) private String caseId;
    @Column(name="review_task_id",length=64,nullable=false) private String reviewTaskId;
    @Column(name="plan_id",length=64,nullable=false) private String planId;
    @Column(name="reviewer_id",length=128,nullable=false) private String reviewerId;
    @Column(name="reviewer_role",length=32,nullable=false) private String reviewerRole;
    @Enumerated(EnumType.STRING) @Column(name="decision_type",length=32,nullable=false) private ApprovalDecisionType decisionType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="original_plan_json",nullable=false,columnDefinition="jsonb") private String originalPlanJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="approved_plan_json",nullable=false,columnDefinition="jsonb") private String approvedPlanJson;
    @Column(name="decision_reason",columnDefinition="text") private String decisionReason;
    @Column(name="action_hash",length=128,nullable=false,unique=true) private String approvalHash;
    @Column(name="review_packet_id",length=64) private String reviewPacketId;
    @Column(name="review_packet_version") private Integer reviewPacketVersion;
    @Column(name="policy_version",length=64) private String policyVersion;
    @Column(name="action_snapshot_hash",length=128) private String actionSnapshotHash;
    @Column(name="approval_expires_at") private OffsetDateTime approvalExpiresAt;
    @Column(name="created_at",nullable=false,updatable=false) private OffsetDateTime createdAt;
    @Column(name="created_by",length=128,nullable=false,updatable=false) private String createdBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.ApprovalRecordEntity()」。
    // 具体功能：「ApprovalRecordEntity.ApprovalRecordEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ApprovalRecordEntity.ApprovalRecordEntity()」的上游创建点包括 「ApprovalRecordEntity.record」。
    // 下游影响：「ApprovalRecordEntity.ApprovalRecordEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ApprovalRecordEntity.ApprovalRecordEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected ApprovalRecordEntity() {}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.ApprovalRecordEntity(String)」。
    // 具体功能：「ApprovalRecordEntity.ApprovalRecordEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ApprovalRecordEntity.ApprovalRecordEntity(String)」的上游创建点包括 「ApprovalRecordEntity.record」。
    // 下游影响：「ApprovalRecordEntity.ApprovalRecordEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ApprovalRecordEntity.ApprovalRecordEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private ApprovalRecordEntity(String id){super(id);}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.record(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String)」。
    // 具体功能：「ApprovalRecordEntity.record(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String)」：记录审批记录：先更新内部状态 「caseId」、「reviewTaskId」、「planId」、「reviewerId」，最终返回「ApprovalRecordEntity」。
    // 上游调用：「ApprovalRecordEntity.record(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String)」的上游调用点包括 「ApprovalRecordEntity.recordFrozen」、「CaseClosureServiceIntegrationTest.seed」、「CaseOutcomeServiceTest.projectsTheLatestHumanDecisionOverTheAdjudicationDraft」。
    // 下游影响：「ApprovalRecordEntity.record(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ApprovalRecordEntity」交给调用方。
    // 系统意义：「ApprovalRecordEntity.record(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ApprovalRecordEntity record(
            String id,String caseId,String taskId,String planId,String reviewerId,
            String role,ApprovalDecisionType decision,String original,String approved,
            String reason,String hash){
        ApprovalRecordEntity record=new ApprovalRecordEntity(id);record.caseId=caseId;
        record.reviewTaskId=taskId;record.planId=planId;record.reviewerId=reviewerId;
        record.reviewerRole=role;record.decisionType=decision;record.originalPlanJson=original;
        record.approvedPlanJson=approved;record.decisionReason=reason;record.approvalHash=hash;
        record.createdBy=reviewerId;return record;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.recordFrozen(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String,String,int,String,String,OffsetDateTime)」。
    // 具体功能：「ApprovalRecordEntity.recordFrozen(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String,String,int,String,String,OffsetDateTime)」：记录冻结：先更新内部状态 「reviewPacketId」、「reviewPacketVersion」、「policyVersion」、「actionSnapshotHash」；实际协作者为 「record」，最终返回「ApprovalRecordEntity」。
    // 上游调用：「ApprovalRecordEntity.recordFrozen(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String,String,int,String,String,OffsetDateTime)」的上游调用点包括 「ReviewApplicationService.persistDecision」、「ToolExecutorServiceIntegrationTest.seed」、「PostReviewOrchestrationServiceIntegrationTest.seed」。
    // 下游影响：「ApprovalRecordEntity.recordFrozen(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String,String,int,String,String,OffsetDateTime)」向下依次触达 「record」；计算结果以「ApprovalRecordEntity」交给调用方。
    // 系统意义：「ApprovalRecordEntity.recordFrozen(String,String,String,String,String,String,ApprovalDecisionType,String,String,String,String,String,int,String,String,OffsetDateTime)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ApprovalRecordEntity recordFrozen(
            String id,
            String caseId,
            String taskId,
            String planId,
            String reviewerId,
            String role,
            ApprovalDecisionType decision,
            String original,
            String approved,
            String reason,
            String idempotencyHash,
            String reviewPacketId,
            int reviewPacketVersion,
            String policyVersion,
            String actionSnapshotHash,
            OffsetDateTime approvalExpiresAt) {
        ApprovalRecordEntity record =
                record(
                        id,
                        caseId,
                        taskId,
                        planId,
                        reviewerId,
                        role,
                        decision,
                        original,
                        approved,
                        reason,
                        idempotencyHash);
        record.reviewPacketId = reviewPacketId;
        record.reviewPacketVersion = reviewPacketVersion;
        record.policyVersion = policyVersion;
        record.actionSnapshotHash = actionSnapshotHash;
        record.approvalExpiresAt = approvalExpiresAt;
        return record;
    }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.prePersist()」。
    // 具体功能：「ApprovalRecordEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「ApprovalRecordEntity.prePersist()」由使用「ApprovalRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ApprovalRecordEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ApprovalRecordEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist void prePersist(){createdAt=OffsetDateTime.now(ZoneOffset.UTC);}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getCaseId()」。
    // 具体功能：「ApprovalRecordEntity.getCaseId()」：读取「ApprovalRecordEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getCaseId()」的上游调用点包括 「PostReviewOrchestrationService.audit」。
    // 下游影响：「ApprovalRecordEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId(){return caseId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getReviewTaskId()」。
    // 具体功能：「ApprovalRecordEntity.getReviewTaskId()」：读取「ApprovalRecordEntity」中的「reviewTaskId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getReviewTaskId()」由使用「ApprovalRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ApprovalRecordEntity.getReviewTaskId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getReviewTaskId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getReviewTaskId(){return reviewTaskId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getPlanId()」。
    // 具体功能：「ApprovalRecordEntity.getPlanId()」：读取「ApprovalRecordEntity」中的「planId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getPlanId()」的上游调用点包括 「CaseClosureService.validateCompletedExecution」、「ToolExecutorService.validateFrozenApproval」。
    // 下游影响：「ApprovalRecordEntity.getPlanId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getPlanId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPlanId(){return planId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getReviewerId()」。
    // 具体功能：「ApprovalRecordEntity.getReviewerId()」：读取「ApprovalRecordEntity」中的「reviewerId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getReviewerId()」的上游调用点包括 「ReviewApplicationService.assertSameIdempotentRequest」。
    // 下游影响：「ApprovalRecordEntity.getReviewerId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getReviewerId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getReviewerId(){return reviewerId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getReviewerRole()」。
    // 具体功能：「ApprovalRecordEntity.getReviewerRole()」：读取「ApprovalRecordEntity」中的「reviewerRole」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getReviewerRole()」的上游调用点包括 「ToolExecutorService.validateFrozenApproval」。
    // 下游影响：「ApprovalRecordEntity.getReviewerRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getReviewerRole()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getReviewerRole(){return reviewerRole;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getDecisionType()」。
    // 具体功能：「ApprovalRecordEntity.getDecisionType()」：读取「ApprovalRecordEntity」中的「decisionType」状态，向 JPA、应用服务或序列化层返回「ApprovalDecisionType」。
    // 上游调用：「ApprovalRecordEntity.getDecisionType()」的上游调用点包括 「CaseClosureService.buildSnapshot」、「CaseOutcomeService.finalDecision」、「PostReviewOrchestrationService.audit」、「ReviewApplicationService.decisionView」。
    // 下游影响：「ApprovalRecordEntity.getDecisionType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ApprovalDecisionType」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getDecisionType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public ApprovalDecisionType getDecisionType(){return decisionType;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getOriginalPlanJson()」。
    // 具体功能：「ApprovalRecordEntity.getOriginalPlanJson()」：读取「ApprovalRecordEntity」中的「originalPlanJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getOriginalPlanJson()」由使用「ApprovalRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ApprovalRecordEntity.getOriginalPlanJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getOriginalPlanJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getOriginalPlanJson(){return originalPlanJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getApprovedPlanJson()」。
    // 具体功能：「ApprovalRecordEntity.getApprovedPlanJson()」：读取「ApprovalRecordEntity」中的「approvedPlanJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getApprovedPlanJson()」的上游调用点包括 「CaseClosureService.validateCompletedExecution」、「CaseClosureService.buildSnapshot」、「CaseOutcomeService.finalDecision」、「ReviewApplicationService.assertSameIdempotentRequest」。
    // 下游影响：「ApprovalRecordEntity.getApprovedPlanJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getApprovedPlanJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getApprovedPlanJson(){return approvedPlanJson;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getDecisionReason()」。
    // 具体功能：「ApprovalRecordEntity.getDecisionReason()」：读取「ApprovalRecordEntity」中的「decisionReason」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getDecisionReason()」的上游调用点包括 「CaseOutcomeService.finalDecision」、「ReviewApplicationService.assertSameIdempotentRequest」。
    // 下游影响：「ApprovalRecordEntity.getDecisionReason()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getDecisionReason()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getDecisionReason(){return decisionReason;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getApprovalHash()」。
    // 具体功能：「ApprovalRecordEntity.getApprovalHash()」：读取「ApprovalRecordEntity」中的「approvalHash」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getApprovalHash()」由使用「ApprovalRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ApprovalRecordEntity.getApprovalHash()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getApprovalHash()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getApprovalHash(){return approvalHash;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getCreatedAt()」。
    // 具体功能：「ApprovalRecordEntity.getCreatedAt()」：读取「ApprovalRecordEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「ApprovalRecordEntity.getCreatedAt()」由使用「ApprovalRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ApprovalRecordEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt(){return createdAt;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getReviewPacketId()」。
    // 具体功能：「ApprovalRecordEntity.getReviewPacketId()」：读取「ApprovalRecordEntity」中的「reviewPacketId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getReviewPacketId()」的上游调用点包括 「ToolExecutorService.validateFrozenApproval」。
    // 下游影响：「ApprovalRecordEntity.getReviewPacketId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getReviewPacketId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getReviewPacketId(){return reviewPacketId;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getReviewPacketVersion()」。
    // 具体功能：「ApprovalRecordEntity.getReviewPacketVersion()」：读取「ApprovalRecordEntity」中的「reviewPacketVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「ApprovalRecordEntity.getReviewPacketVersion()」的上游调用点包括 「ToolExecutorService.validateFrozenApproval」。
    // 下游影响：「ApprovalRecordEntity.getReviewPacketVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getReviewPacketVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getReviewPacketVersion(){return reviewPacketVersion == null ? 0 : reviewPacketVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getPolicyVersion()」。
    // 具体功能：「ApprovalRecordEntity.getPolicyVersion()」：读取「ApprovalRecordEntity」中的「policyVersion」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getPolicyVersion()」由使用「ApprovalRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ApprovalRecordEntity.getPolicyVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getPolicyVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPolicyVersion(){return policyVersion;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getActionSnapshotHash()」。
    // 具体功能：「ApprovalRecordEntity.getActionSnapshotHash()」：读取「ApprovalRecordEntity」中的「actionSnapshotHash」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ApprovalRecordEntity.getActionSnapshotHash()」的上游调用点包括 「ToolExecutorService.validateFrozenApproval」。
    // 下游影响：「ApprovalRecordEntity.getActionSnapshotHash()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getActionSnapshotHash()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getActionSnapshotHash(){return actionSnapshotHash;}
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ApprovalRecordEntity.getApprovalExpiresAt()」。
    // 具体功能：「ApprovalRecordEntity.getApprovalExpiresAt()」：读取「ApprovalRecordEntity」中的「approvalExpiresAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「ApprovalRecordEntity.getApprovalExpiresAt()」的上游调用点包括 「ToolExecutorService.validateFrozenApproval」。
    // 下游影响：「ApprovalRecordEntity.getApprovalExpiresAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「ApprovalRecordEntity.getApprovalExpiresAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getApprovalExpiresAt(){return approvalExpiresAt;}
}
