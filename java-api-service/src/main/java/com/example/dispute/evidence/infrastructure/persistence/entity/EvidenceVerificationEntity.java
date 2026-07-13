/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：映射证据核验数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「getEvidenceId」、「getVerificationVersion」、「getVerificationStatus」、「getDeterministicChecksJson」、「getAgentFindingsJson」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【证据与版本化卷宗 / JPA 实体层】类型「EvidenceVerificationEntity」。
// 类型职责：映射证据核验数据库记录并保存可审计状态；本类型显式提供 「EvidenceVerificationEntity」、「EvidenceVerificationEntity」、「create」、「getEvidenceId」、「getVerificationVersion」、「getVerificationStatus」。
// 协作关系：主要由 「EvidenceAgentTurnService.persistEvidenceAssessments」、「EvidenceVerificationService.verify」、「EvidenceVerificationService.view」、「IncludedEvidence.status」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Immutable
@Table(name = "evidence_verification")
public class EvidenceVerificationEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "evidence_id", length = 64, nullable = false)
    private String evidenceId;
    @Column(name = "verification_version", nullable = false)
    private int verificationVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 32, nullable = false)
    private EvidenceVerificationStatus verificationStatus;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deterministic_checks_json", nullable = false, columnDefinition = "jsonb")
    private String deterministicChecksJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_findings_json", nullable = false, columnDefinition = "jsonb")
    private String agentFindingsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons_json", nullable = false, columnDefinition = "jsonb")
    private String reasonsJson;
    @Column(name = "requires_human_review", nullable = false)
    private boolean requiresHumanReview;
    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;
    @Column(name = "verified_by", length = 128, nullable = false)
    private String verifiedBy;
    @Column(name = "agent_run_id", length = 64)
    private String agentRunId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "trace_id", length = 128)
    private String traceId;

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.EvidenceVerificationEntity()」。
    // 具体功能：「EvidenceVerificationEntity.EvidenceVerificationEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceVerificationEntity.EvidenceVerificationEntity()」的上游创建点包括 「EvidenceVerificationEntity.create」。
    // 下游影响：「EvidenceVerificationEntity.EvidenceVerificationEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceVerificationEntity.EvidenceVerificationEntity()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected EvidenceVerificationEntity() {}
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.EvidenceVerificationEntity(String)」。
    // 具体功能：「EvidenceVerificationEntity.EvidenceVerificationEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceVerificationEntity.EvidenceVerificationEntity(String)」的上游创建点包括 「EvidenceVerificationEntity.create」。
    // 下游影响：「EvidenceVerificationEntity.EvidenceVerificationEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceVerificationEntity.EvidenceVerificationEntity(String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private EvidenceVerificationEntity(String id) { super(id); }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.create(String,String,String,int,EvidenceVerificationStatus,String,String,String,boolean,Instant,String,String)」。
    // 具体功能：「EvidenceVerificationEntity.create(String,String,String,int,EvidenceVerificationStatus,String,String,String,boolean,Instant,String,String)」：创建证据核验：先更新内部状态 「caseId」、「evidenceId」、「verificationVersion」、「verificationStatus」；实际协作者为 「Objects.requireNonNull」，最终返回「EvidenceVerificationEntity」。
    // 上游调用：「EvidenceVerificationEntity.create(String,String,String,int,EvidenceVerificationStatus,String,String,String,boolean,Instant,String,String)」的上游调用点包括 「EvidenceVerificationService.verify」、「EvidenceAgentTurnService.persistEvidenceAssessments」、「EvidenceDossierFreezerTest.frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults」、「EvidenceDossierFreezerTest.lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact」。
    // 下游影响：「EvidenceVerificationEntity.create(String,String,String,int,EvidenceVerificationStatus,String,String,String,boolean,Instant,String,String)」向下依次触达 「Objects.requireNonNull」；计算结果以「EvidenceVerificationEntity」交给调用方。
    // 系统意义：「EvidenceVerificationEntity.create(String,String,String,int,EvidenceVerificationStatus,String,String,String,boolean,Instant,String,String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public static EvidenceVerificationEntity create(
            String id, String caseId, String evidenceId, int version,
            EvidenceVerificationStatus status, String checksJson, String agentJson,
            String reasonsJson, boolean requiresHumanReview, Instant verifiedAt,
            String verifiedBy, String traceId) {
        EvidenceVerificationEntity entity = new EvidenceVerificationEntity(id);
        entity.caseId = caseId;
        entity.evidenceId = evidenceId;
        entity.verificationVersion = version;
        entity.verificationStatus = Objects.requireNonNull(status);
        entity.deterministicChecksJson = checksJson;
        entity.agentFindingsJson = agentJson;
        entity.reasonsJson = reasonsJson;
        entity.requiresHumanReview = requiresHumanReview;
        entity.verifiedAt = verifiedAt;
        entity.verifiedBy = verifiedBy;
        entity.createdAt = verifiedAt;
        entity.traceId = traceId;
        return entity;
    }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.getEvidenceId()」。
    // 具体功能：「EvidenceVerificationEntity.getEvidenceId()」：读取「EvidenceVerificationEntity」中的「evidenceId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceVerificationEntity.getEvidenceId()」的上游调用点包括 「EvidenceVerificationService.view」。
    // 下游影响：「EvidenceVerificationEntity.getEvidenceId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceVerificationEntity.getEvidenceId()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getEvidenceId() { return evidenceId; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.getVerificationVersion()」。
    // 具体功能：「EvidenceVerificationEntity.getVerificationVersion()」：读取「EvidenceVerificationEntity」中的「verificationVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「EvidenceVerificationEntity.getVerificationVersion()」的上游调用点包括 「EvidenceVerificationService.view」。
    // 下游影响：「EvidenceVerificationEntity.getVerificationVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「EvidenceVerificationEntity.getVerificationVersion()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public int getVerificationVersion() { return verificationVersion; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.getVerificationStatus()」。
    // 具体功能：「EvidenceVerificationEntity.getVerificationStatus()」：读取「EvidenceVerificationEntity」中的「verificationStatus」状态，向 JPA、应用服务或序列化层返回「EvidenceVerificationStatus」。
    // 上游调用：「EvidenceVerificationEntity.getVerificationStatus()」的上游调用点包括 「IncludedEvidence.status」、「EvidenceVerificationService.view」。
    // 下游影响：「EvidenceVerificationEntity.getVerificationStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidenceVerificationStatus」交给调用方。
    // 系统意义：「EvidenceVerificationEntity.getVerificationStatus()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public EvidenceVerificationStatus getVerificationStatus() { return verificationStatus; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.getDeterministicChecksJson()」。
    // 具体功能：「EvidenceVerificationEntity.getDeterministicChecksJson()」：读取「EvidenceVerificationEntity」中的「deterministicChecksJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceVerificationEntity.getDeterministicChecksJson()」由使用「EvidenceVerificationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceVerificationEntity.getDeterministicChecksJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceVerificationEntity.getDeterministicChecksJson()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getDeterministicChecksJson() { return deterministicChecksJson; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.getAgentFindingsJson()」。
    // 具体功能：「EvidenceVerificationEntity.getAgentFindingsJson()」：读取「EvidenceVerificationEntity」中的「agentFindingsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceVerificationEntity.getAgentFindingsJson()」由使用「EvidenceVerificationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceVerificationEntity.getAgentFindingsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceVerificationEntity.getAgentFindingsJson()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getAgentFindingsJson() { return agentFindingsJson; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.getReasonsJson()」。
    // 具体功能：「EvidenceVerificationEntity.getReasonsJson()」：读取「EvidenceVerificationEntity」中的「reasonsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceVerificationEntity.getReasonsJson()」由使用「EvidenceVerificationEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceVerificationEntity.getReasonsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceVerificationEntity.getReasonsJson()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getReasonsJson() { return reasonsJson; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.isRequiresHumanReview()」。
    // 具体功能：「EvidenceVerificationEntity.isRequiresHumanReview()」：判断是否Requires人工审核，最终返回「boolean」。
    // 上游调用：「EvidenceVerificationEntity.isRequiresHumanReview()」的上游调用点包括 「EvidenceVerificationService.view」。
    // 下游影响：「EvidenceVerificationEntity.isRequiresHumanReview()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceVerificationEntity.isRequiresHumanReview()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public boolean isRequiresHumanReview() { return requiresHumanReview; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceVerificationEntity.getVerifiedAt()」。
    // 具体功能：「EvidenceVerificationEntity.getVerifiedAt()」：读取「EvidenceVerificationEntity」中的「verifiedAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「EvidenceVerificationEntity.getVerifiedAt()」的上游调用点包括 「EvidenceVerificationService.view」。
    // 下游影响：「EvidenceVerificationEntity.getVerifiedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「EvidenceVerificationEntity.getVerifiedAt()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public Instant getVerifiedAt() { return verifiedAt; }
}
