/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射证据Item数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「uploaded」、「prePersist」、「preUpdate」、「getCaseId」、「getDossierId」、「getEvidenceType」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.ParseStatus;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
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

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「EvidenceItemEntity」。
// 类型职责：映射证据Item数据库记录并保存可审计状态；本类型显式提供 「EvidenceItemEntity」、「EvidenceItemEntity」、「uploaded」、「prePersist」、「preUpdate」、「getCaseId」。
// 协作关系：主要由 「EvidenceAgentTurnService.evidenceVisibleToSession」、「EvidenceApplicationService.canReadContent」、「EvidenceApplicationService.isSubmittedEvidence」、「EvidenceApplicationService.loadContent」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "evidence_item")
public class EvidenceItemEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "dossier_id", length = 64, nullable = false)
    private String dossierId;

    @Column(name = "evidence_type", length = 64, nullable = false)
    private String evidenceType;

    @Column(name = "source_type", length = 64, nullable = false)
    private String sourceType;

    @Column(name = "submitted_by_role", length = 32, nullable = false)
    private String submittedByRole;

    @Column(name = "submitted_by_id", length = 128, nullable = false)
    private String submittedById;

    @Column(name = "file_bucket", length = 128)
    private String fileBucket;

    @Column(name = "file_object_key", length = 512)
    private String fileObjectKey;

    @Column(name = "file_hash", length = 128)
    private String fileHash;

    @Column(name = "original_filename", length = 512)
    private String originalFilename;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "parsed_text", columnDefinition = "text")
    private String parsedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", length = 32, nullable = false)
    private ParseStatus parseStatus;

    @Column(name = "visibility", length = 32, nullable = false)
    private String visibility;

    @Column(name = "desensitized", nullable = false)
    private boolean desensitized;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extraction_json", nullable = false, columnDefinition = "jsonb")
    private String extractionJson;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_status", length = 32, nullable = false)
    private EvidenceSubmissionStatus submissionStatus;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "submission_batch_id", length = 64)
    private String submissionBatchId;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.EvidenceItemEntity()」。
    // 具体功能：「EvidenceItemEntity.EvidenceItemEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceItemEntity.EvidenceItemEntity()」的上游创建点包括 「EvidenceItemEntity.uploaded」。
    // 下游影响：「EvidenceItemEntity.EvidenceItemEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceItemEntity.EvidenceItemEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected EvidenceItemEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.EvidenceItemEntity(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」。
    // 具体功能：「EvidenceItemEntity.EvidenceItemEntity(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」：使用 「id」(String)、「caseId」(String)、「dossierId」(String)、「evidenceType」(String)、「sourceType」(String)、「submittedByRole」(String)、「submittedById」(String)、「fileBucket」(String)、「fileObjectKey」(String)、「fileHash」(String)、「originalFilename」(String)、「contentType」(String)、「fileSize」(long)、「visibility」(String)、「occurredAt」(OffsetDateTime) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceItemEntity.EvidenceItemEntity(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」的上游创建点包括 「EvidenceItemEntity.uploaded」。
    // 下游影响：「EvidenceItemEntity.EvidenceItemEntity(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceItemEntity.EvidenceItemEntity(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private EvidenceItemEntity(
            String id,
            String caseId,
            String dossierId,
            String evidenceType,
            String sourceType,
            String submittedByRole,
            String submittedById,
            String fileBucket,
            String fileObjectKey,
            String fileHash,
            String originalFilename,
            String contentType,
            long fileSize,
            String visibility,
            OffsetDateTime occurredAt) {
        super(id);
        this.caseId = caseId;
        this.dossierId = dossierId;
        this.evidenceType = evidenceType;
        this.sourceType = sourceType;
        this.submittedByRole = submittedByRole;
        this.submittedById = submittedById;
        this.fileBucket = fileBucket;
        this.fileObjectKey = fileObjectKey;
        this.fileHash = fileHash;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.parseStatus = ParseStatus.PENDING;
        this.visibility = visibility;
        this.desensitized = false;
        this.metadataJson = "{}";
        this.extractionJson = "{}";
        this.occurredAt = occurredAt;
        this.submissionStatus = EvidenceSubmissionStatus.PENDING_SUBMISSION;
        this.createdBy = submittedById;
        this.updatedBy = submittedById;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.uploaded(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」。
    // 具体功能：「EvidenceItemEntity.uploaded(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」：上传uploaded，最终返回「EvidenceItemEntity」。
    // 上游调用：「EvidenceItemEntity.uploaded(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」的上游调用点包括 「EvidenceApplicationService.upload」、「EvidenceApplicationServiceTest.reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence」、「EvidenceApplicationServiceTest.evidenceEntity」、「EvidenceCatalogServiceTest.evidence」。
    // 下游影响：「EvidenceItemEntity.uploaded(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidenceItemEntity」交给调用方。
    // 系统意义：「EvidenceItemEntity.uploaded(String,String,String,String,String,String,String,String,String,String,String,String,long,String,OffsetDateTime)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static EvidenceItemEntity uploaded(
            String id,
            String caseId,
            String dossierId,
            String evidenceType,
            String sourceType,
            String submittedByRole,
            String submittedById,
            String fileBucket,
            String fileObjectKey,
            String fileHash,
            String originalFilename,
            String contentType,
            long fileSize,
            String visibility,
            OffsetDateTime occurredAt) {
        return new EvidenceItemEntity(
                id,
                caseId,
                dossierId,
                evidenceType,
                sourceType,
                submittedByRole,
                submittedById,
                fileBucket,
                fileObjectKey,
                fileHash,
                originalFilename,
                contentType,
                fileSize,
                visibility,
                occurredAt);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.prePersist()」。
    // 具体功能：「EvidenceItemEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「EvidenceItemEntity.prePersist()」由使用「EvidenceItemEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceItemEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceItemEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.preUpdate()」。
    // 具体功能：「EvidenceItemEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「EvidenceItemEntity.preUpdate()」由使用「EvidenceItemEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceItemEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceItemEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getCaseId()」。
    // 具体功能：「EvidenceItemEntity.getCaseId()」：读取「EvidenceItemEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getCaseId()」的上游调用点包括 「EvidenceApplicationService.toView」。
    // 下游影响：「EvidenceItemEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getDossierId()」。
    // 具体功能：「EvidenceItemEntity.getDossierId()」：读取「EvidenceItemEntity」中的「dossierId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getDossierId()」的上游调用点包括 「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getDossierId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getDossierId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getDossierId() {
        return dossierId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getEvidenceType()」。
    // 具体功能：「EvidenceItemEntity.getEvidenceType()」：读取「EvidenceItemEntity」中的「evidenceType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getEvidenceType()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceCatalogService.project」、「EvidenceDossierFreezer.relevanceScore」、「EvidenceDossierFreezer.factId」。
    // 下游影响：「EvidenceItemEntity.getEvidenceType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getEvidenceType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getEvidenceType() {
        return evidenceType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getSourceType()」。
    // 具体功能：「EvidenceItemEntity.getSourceType()」：读取「EvidenceItemEntity」中的「sourceType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getSourceType()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceCatalogService.project」、「EvidenceDossierFreezer.relevanceScore」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getSourceType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getSourceType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSourceType() {
        return sourceType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getSubmittedByRole()」。
    // 具体功能：「EvidenceItemEntity.getSubmittedByRole()」：读取「EvidenceItemEntity」中的「submittedByRole」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getSubmittedByRole()」的上游调用点包括 「EvidenceApplicationService.canReadContent」、「EvidenceCatalogService.canSeeCatalogItem」、「EvidenceCatalogService.project」、「EvidenceCatalogService.isHearingPartySharedEvidence」。
    // 下游影响：「EvidenceItemEntity.getSubmittedByRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getSubmittedByRole()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSubmittedByRole() {
        return submittedByRole;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getSubmittedById()」。
    // 具体功能：「EvidenceItemEntity.getSubmittedById()」：读取「EvidenceItemEntity」中的「submittedById」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getSubmittedById()」的上游调用点包括 「EvidenceApplicationService.canReadContent」、「EvidenceCatalogService.canSeeCatalogItem」、「EvidenceCatalogService.project」、「EvidenceCatalogService.isHearingPartySharedEvidence」。
    // 下游影响：「EvidenceItemEntity.getSubmittedById()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getSubmittedById()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSubmittedById() {
        return submittedById;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getFileBucket()」。
    // 具体功能：「EvidenceItemEntity.getFileBucket()」：读取「EvidenceItemEntity」中的「fileBucket」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getFileBucket()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceApplicationService.loadContent」。
    // 下游影响：「EvidenceItemEntity.getFileBucket()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getFileBucket()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getFileBucket() {
        return fileBucket;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getFileObjectKey()」。
    // 具体功能：「EvidenceItemEntity.getFileObjectKey()」：读取「EvidenceItemEntity」中的「fileObjectKey」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getFileObjectKey()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceApplicationService.loadContent」。
    // 下游影响：「EvidenceItemEntity.getFileObjectKey()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getFileObjectKey()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getFileObjectKey() {
        return fileObjectKey;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getFileHash()」。
    // 具体功能：「EvidenceItemEntity.getFileHash()」：读取「EvidenceItemEntity」中的「fileHash」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getFileHash()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceDossierFreezer.completenessScore」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getFileHash()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getFileHash()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getFileHash() {
        return fileHash;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getOriginalFilename()」。
    // 具体功能：「EvidenceItemEntity.getOriginalFilename()」：读取「EvidenceItemEntity」中的「originalFilename」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getOriginalFilename()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceApplicationService.loadContent」、「EvidenceCatalogService.project」、「EvidenceDossierFreezer.relevanceScore」。
    // 下游影响：「EvidenceItemEntity.getOriginalFilename()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getOriginalFilename()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getOriginalFilename() {
        return originalFilename;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getContentType()」。
    // 具体功能：「EvidenceItemEntity.getContentType()」：读取「EvidenceItemEntity」中的「contentType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getContentType()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceApplicationService.loadContent」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getContentType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getContentType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getContentType() {
        return contentType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getFileSize()」。
    // 具体功能：「EvidenceItemEntity.getFileSize()」：读取「EvidenceItemEntity」中的「fileSize」状态，向 JPA、应用服务或序列化层返回「Long」。
    // 上游调用：「EvidenceItemEntity.getFileSize()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getFileSize()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Long」交给调用方。
    // 系统意义：「EvidenceItemEntity.getFileSize()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public Long getFileSize() {
        return fileSize;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getParseStatus()」。
    // 具体功能：「EvidenceItemEntity.getParseStatus()」：读取「EvidenceItemEntity」中的「parseStatus」状态，向 JPA、应用服务或序列化层返回「ParseStatus」。
    // 上游调用：「EvidenceItemEntity.getParseStatus()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceDossierFreezer.completenessScore」、「EvidenceDossierFreezer.riskFlags」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getParseStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ParseStatus」交给调用方。
    // 系统意义：「EvidenceItemEntity.getParseStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public ParseStatus getParseStatus() {
        return parseStatus;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getVisibility()」。
    // 具体功能：「EvidenceItemEntity.getVisibility()」：读取「EvidenceItemEntity」中的「visibility」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getVisibility()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceCatalogService.project」、「EvidenceCatalogService.isHearingPartySharedEvidence」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getVisibility()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getVisibility()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getVisibility() {
        return visibility;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.isDesensitized()」。
    // 具体功能：「EvidenceItemEntity.isDesensitized()」：判断是否Desensitized，最终返回「boolean」。
    // 上游调用：「EvidenceItemEntity.isDesensitized()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.isDesensitized()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceItemEntity.isDesensitized()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public boolean isDesensitized() {
        return desensitized;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getOccurredAt()」。
    // 具体功能：「EvidenceItemEntity.getOccurredAt()」：读取「EvidenceItemEntity」中的「occurredAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「EvidenceItemEntity.getOccurredAt()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceDossierFreezer.completenessScore」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getOccurredAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「EvidenceItemEntity.getOccurredAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getCreatedAt()」。
    // 具体功能：「EvidenceItemEntity.getCreatedAt()」：读取「EvidenceItemEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「EvidenceItemEntity.getCreatedAt()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「EvidenceItemEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getDeletedAt()」。
    // 具体功能：「EvidenceItemEntity.getDeletedAt()」：读取「EvidenceItemEntity」中的「deletedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「EvidenceItemEntity.getDeletedAt()」由使用「EvidenceItemEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceItemEntity.getDeletedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「EvidenceItemEntity.getDeletedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getSubmissionStatus()」。
    // 具体功能：「EvidenceItemEntity.getSubmissionStatus()」：读取「EvidenceItemEntity」中的「submissionStatus」状态，向 JPA、应用服务或序列化层返回「EvidenceSubmissionStatus」。
    // 上游调用：「EvidenceItemEntity.getSubmissionStatus()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceApplicationService.isSubmittedEvidence」、「EvidenceCatalogService.project」、「EvidenceCatalogService.isHearingPartySharedEvidence」。
    // 下游影响：「EvidenceItemEntity.getSubmissionStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidenceSubmissionStatus」交给调用方。
    // 系统意义：「EvidenceItemEntity.getSubmissionStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public EvidenceSubmissionStatus getSubmissionStatus() {
        return submissionStatus == null
                ? EvidenceSubmissionStatus.SUBMITTED
                : submissionStatus;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getSubmittedAt()」。
    // 具体功能：「EvidenceItemEntity.getSubmittedAt()」：读取「EvidenceItemEntity」中的「submittedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「EvidenceItemEntity.getSubmittedAt()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceCatalogService.project」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getSubmittedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「EvidenceItemEntity.getSubmittedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getSubmittedAt() {
        return submittedAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getSubmissionBatchId()」。
    // 具体功能：「EvidenceItemEntity.getSubmissionBatchId()」：读取「EvidenceItemEntity」中的「submissionBatchId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getSubmissionBatchId()」的上游调用点包括 「EvidenceApplicationService.toView」、「EvidenceCatalogService.project」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getSubmissionBatchId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getSubmissionBatchId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSubmissionBatchId() {
        return submissionBatchId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getParsedText()」。
    // 具体功能：「EvidenceItemEntity.getParsedText()」：读取「EvidenceItemEntity」中的「parsedText」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getParsedText()」的上游调用点包括 「EvidenceCatalogService.project」、「EvidenceDossierFreezer.relevanceScore」、「EvidenceDossierFreezer.factId」、「EvidenceDossierFreezer.claimedFact」。
    // 下游影响：「EvidenceItemEntity.getParsedText()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getParsedText()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getParsedText() {
        return parsedText;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getExtractionJson()」。
    // 具体功能：「EvidenceItemEntity.getExtractionJson()」：读取「EvidenceItemEntity」中的「extractionJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getExtractionJson()」的上游调用点包括 「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getExtractionJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getExtractionJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getExtractionJson() {
        return extractionJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.getMetadataJson()」。
    // 具体功能：「EvidenceItemEntity.getMetadataJson()」：读取「EvidenceItemEntity」中的「metadataJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceItemEntity.getMetadataJson()」的上游调用点包括 「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceItemEntity.getMetadataJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceItemEntity.getMetadataJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getMetadataJson() {
        return metadataJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.authorizeModelProcessing(String,String)」。
    // 具体功能：「EvidenceItemEntity.authorizeModelProcessing(String,String)」：授权ModelProcessing：先更新内部状态 「metadataJson」、「updatedBy」；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「EvidenceItemEntity.authorizeModelProcessing(String,String)」由使用「EvidenceItemEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceItemEntity.authorizeModelProcessing(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceItemEntity.authorizeModelProcessing(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void authorizeModelProcessing(String authorizationMetadataJson, String actorId) {
        if (authorizationMetadataJson == null || authorizationMetadataJson.isBlank()) {
            throw new IllegalArgumentException("authorization metadata must not be blank");
        }
        this.metadataJson = authorizationMetadataJson;
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.applyParseSuccess(String,String,String)」。
    // 具体功能：「EvidenceItemEntity.applyParseSuccess(String,String,String)」：应用解析成功：先更新内部状态 「parsedText」、「extractionJson」、「parseStatus」、「updatedBy」；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「EvidenceItemEntity.applyParseSuccess(String,String,String)」由使用「EvidenceItemEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceItemEntity.applyParseSuccess(String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceItemEntity.applyParseSuccess(String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void applyParseSuccess(
            String text, String extractionJson, String actorId) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("parsed text must not be blank");
        }
        this.parsedText = text;
        this.extractionJson = extractionJson;
        this.parseStatus = ParseStatus.SUCCEEDED;
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.applyParseFailure(String,String)」。
    // 具体功能：「EvidenceItemEntity.applyParseFailure(String,String)」：应用解析失败：先更新内部状态 「parsedText」、「extractionJson」、「parseStatus」、「updatedBy」，最终返回「void」。
    // 上游调用：「EvidenceItemEntity.applyParseFailure(String,String)」由使用「EvidenceItemEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceItemEntity.applyParseFailure(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceItemEntity.applyParseFailure(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void applyParseFailure(String extractionJson, String actorId) {
        this.parsedText = null;
        this.extractionJson = extractionJson;
        this.parseStatus = ParseStatus.FAILED;
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.markSubmitted(String,OffsetDateTime,String)」。
    // 具体功能：「EvidenceItemEntity.markSubmitted(String,OffsetDateTime,String)」：标记Submitted：先更新内部状态 「submissionStatus」、「submissionBatchId」、「submittedAt」、「updatedBy」；实际协作者为 「getSubmissionStatus」；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「EvidenceItemEntity.markSubmitted(String,OffsetDateTime,String)」的上游调用点包括 「EvidenceItemEntity.markSubmittedForParties」。
    // 下游影响：「EvidenceItemEntity.markSubmitted(String,OffsetDateTime,String)」向下依次触达 「getSubmissionStatus」。
    // 系统意义：「EvidenceItemEntity.markSubmitted(String,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void markSubmitted(String batchId, OffsetDateTime submittedAt, String actorId) {
        if (deletedAt != null) {
            throw new IllegalStateException("deleted evidence cannot be submitted");
        }
        if (getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED) {
            throw new IllegalStateException("evidence has already been submitted");
        }
        this.submissionStatus = EvidenceSubmissionStatus.SUBMITTED;
        this.submissionBatchId = batchId;
        this.submittedAt = submittedAt;
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.markSubmittedForParties(String,OffsetDateTime,String)」。
    // 具体功能：「EvidenceItemEntity.markSubmittedForParties(String,OffsetDateTime,String)」：标记Submitted面向参与方：先更新内部状态 「visibility」；实际协作者为 「markSubmitted」；处理的关键状态/协议值包括 「PARTIES」，最终返回「void」。
    // 上游调用：「EvidenceItemEntity.markSubmittedForParties(String,OffsetDateTime,String)」由使用「EvidenceItemEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceItemEntity.markSubmittedForParties(String,OffsetDateTime,String)」向下依次触达 「markSubmitted」。
    // 系统意义：「EvidenceItemEntity.markSubmittedForParties(String,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void markSubmittedForParties(
            String batchId, OffsetDateTime submittedAt, String actorId) {
        markSubmitted(batchId, submittedAt, actorId);
        this.visibility = "PARTIES";
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceItemEntity.deletePending(OffsetDateTime,String)」。
    // 具体功能：「EvidenceItemEntity.deletePending(OffsetDateTime,String)」：删除待处理：先更新内部状态 「submissionStatus」、「deletedAt」、「updatedBy」；实际协作者为 「getSubmissionStatus」；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「EvidenceItemEntity.deletePending(OffsetDateTime,String)」由使用「EvidenceItemEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceItemEntity.deletePending(OffsetDateTime,String)」向下依次触达 「getSubmissionStatus」。
    // 系统意义：「EvidenceItemEntity.deletePending(OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void deletePending(OffsetDateTime deletedAt, String actorId) {
        if (getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED) {
            throw new IllegalStateException("submitted evidence cannot be deleted");
        }
        this.submissionStatus = EvidenceSubmissionStatus.VOIDED;
        this.deletedAt = deletedAt;
        this.updatedBy = actorId;
    }
}
