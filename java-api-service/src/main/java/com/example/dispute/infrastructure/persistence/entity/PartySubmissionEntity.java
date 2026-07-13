/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射当事方提交数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「submit」、「prePersist」、「preUpdate」、「getCaseId」、「getSubmittedByRole」、「getSubmittedById」；映射案件全链路实体并提供 Spring Data 仓储查询。
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

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「PartySubmissionEntity」。
// 类型职责：映射当事方提交数据库记录并保存可审计状态；本类型显式提供 「PartySubmissionEntity」、「PartySubmissionEntity」、「submit」、「prePersist」、「preUpdate」、「getCaseId」。
// 协作关系：主要由 「WorkflowApplicationService.submitPartyEvidence」、「HearingPersistenceIntegrationTest.persistsStateAppendOnlyRecordsDraftAndPartySubmission」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "dispute_submission")
public class PartySubmissionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "evidence_request_id", length = 64)
    private String evidenceRequestId;
    @Column(name = "submitted_by_role", length = 32, nullable = false)
    private String submittedByRole;
    @Column(name = "submitted_by_id", length = 128, nullable = false)
    private String submittedById;
    @Column(name = "submission_type", length = 64, nullable = false)
    private String submissionType;
    @Column(name = "submission_text", columnDefinition = "text")
    private String submissionText;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submission_json", nullable = false, columnDefinition = "jsonb")
    private String submissionJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachment_ids_json", nullable = false, columnDefinition = "jsonb")
    private String attachmentIdsJson;
    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.PartySubmissionEntity()」。
    // 具体功能：「PartySubmissionEntity.PartySubmissionEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「PartySubmissionEntity.PartySubmissionEntity()」的上游创建点包括 「PartySubmissionEntity.submit」。
    // 下游影响：「PartySubmissionEntity.PartySubmissionEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PartySubmissionEntity.PartySubmissionEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected PartySubmissionEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.PartySubmissionEntity(String)」。
    // 具体功能：「PartySubmissionEntity.PartySubmissionEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「PartySubmissionEntity.PartySubmissionEntity(String)」的上游创建点包括 「PartySubmissionEntity.submit」。
    // 下游影响：「PartySubmissionEntity.PartySubmissionEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PartySubmissionEntity.PartySubmissionEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private PartySubmissionEntity(String id) { super(id); }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.submit(String,String,String,String,String,String,String,String,String)」。
    // 具体功能：「PartySubmissionEntity.submit(String,String,String,String,String,String,String,String,String)」：提交当事方提交：先更新内部状态 「caseId」、「evidenceRequestId」、「submittedByRole」、「submittedById」，最终返回「PartySubmissionEntity」。
    // 上游调用：「PartySubmissionEntity.submit(String,String,String,String,String,String,String,String,String)」的上游调用点包括 「WorkflowApplicationService.submitPartyEvidence」、「HearingPersistenceIntegrationTest.persistsStateAppendOnlyRecordsDraftAndPartySubmission」。
    // 下游影响：「PartySubmissionEntity.submit(String,String,String,String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「PartySubmissionEntity」交给调用方。
    // 系统意义：「PartySubmissionEntity.submit(String,String,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static PartySubmissionEntity submit(
            String id, String caseId, String requestId, String role, String actorId,
            String type, String text, String submissionJson, String attachmentIdsJson) {
        PartySubmissionEntity submission = new PartySubmissionEntity(id);
        submission.caseId = caseId;
        submission.evidenceRequestId = requestId;
        submission.submittedByRole = role;
        submission.submittedById = actorId;
        submission.submissionType = type;
        submission.submissionText = text;
        submission.submissionJson = submissionJson;
        submission.attachmentIdsJson = attachmentIdsJson;
        submission.acceptedAt = OffsetDateTime.now(ZoneOffset.UTC);
        submission.createdBy = actorId;
        submission.updatedBy = actorId;
        return submission;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.prePersist()」。
    // 具体功能：「PartySubmissionEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「PartySubmissionEntity.prePersist()」由使用「PartySubmissionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「PartySubmissionEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PartySubmissionEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.preUpdate()」。
    // 具体功能：「PartySubmissionEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「PartySubmissionEntity.preUpdate()」由使用「PartySubmissionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「PartySubmissionEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PartySubmissionEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate void preUpdate() { updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.getCaseId()」。
    // 具体功能：「PartySubmissionEntity.getCaseId()」：读取「PartySubmissionEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PartySubmissionEntity.getCaseId()」由使用「PartySubmissionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「PartySubmissionEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PartySubmissionEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() { return caseId; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.getSubmittedByRole()」。
    // 具体功能：「PartySubmissionEntity.getSubmittedByRole()」：读取「PartySubmissionEntity」中的「submittedByRole」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PartySubmissionEntity.getSubmittedByRole()」由使用「PartySubmissionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「PartySubmissionEntity.getSubmittedByRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PartySubmissionEntity.getSubmittedByRole()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSubmittedByRole() { return submittedByRole; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.getSubmittedById()」。
    // 具体功能：「PartySubmissionEntity.getSubmittedById()」：读取「PartySubmissionEntity」中的「submittedById」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PartySubmissionEntity.getSubmittedById()」由使用「PartySubmissionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「PartySubmissionEntity.getSubmittedById()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PartySubmissionEntity.getSubmittedById()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSubmittedById() { return submittedById; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.getSubmissionText()」。
    // 具体功能：「PartySubmissionEntity.getSubmissionText()」：读取「PartySubmissionEntity」中的「submissionText」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PartySubmissionEntity.getSubmissionText()」由使用「PartySubmissionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「PartySubmissionEntity.getSubmissionText()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PartySubmissionEntity.getSubmissionText()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSubmissionText() { return submissionText; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「PartySubmissionEntity.getAttachmentIdsJson()」。
    // 具体功能：「PartySubmissionEntity.getAttachmentIdsJson()」：读取「PartySubmissionEntity」中的「attachmentIdsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「PartySubmissionEntity.getAttachmentIdsJson()」由使用「PartySubmissionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「PartySubmissionEntity.getAttachmentIdsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「PartySubmissionEntity.getAttachmentIdsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getAttachmentIdsJson() { return attachmentIdsJson; }
}
