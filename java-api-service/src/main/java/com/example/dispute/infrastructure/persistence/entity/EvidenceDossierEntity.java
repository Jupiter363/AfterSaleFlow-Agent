/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射证据卷宗数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「firstBuild」、「collecting」、「frozen」、「rebuild」、「prePersist」、「preUpdate」；映射案件全链路实体并提供 Spring Data 仓储查询。
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

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「EvidenceDossierEntity」。
// 类型职责：映射证据卷宗数据库记录并保存可审计状态；本类型显式提供 「EvidenceDossierEntity」、「EvidenceDossierEntity」、「firstBuild」、「collecting」、「frozen」、「rebuild」。
// 协作关系：主要由 「ActiveCourtroomContextAssembler.evidenceDossierContext」、「EvidenceApplicationService.buildDossier」、「EvidenceApplicationService.upload」、「EvidenceDossierFreezer.createFrozen」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "evidence_dossier")
public class EvidenceDossierEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "dossier_status", length = 32, nullable = false)
    private String dossierStatus;

    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json", nullable = false, columnDefinition = "jsonb")
    private String summaryJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "timeline_json", nullable = false, columnDefinition = "jsonb")
    private String timelineJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matrix_summary_json", nullable = false, columnDefinition = "jsonb")
    private String matrixSummaryJson;

    @Column(name = "built_at")
    private OffsetDateTime builtAt;

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

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.EvidenceDossierEntity()」。
    // 具体功能：「EvidenceDossierEntity.EvidenceDossierEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceDossierEntity.EvidenceDossierEntity()」的上游创建点包括 「EvidenceDossierEntity.firstBuild」、「EvidenceDossierEntity.collecting」、「EvidenceDossierEntity.frozen」。
    // 下游影响：「EvidenceDossierEntity.EvidenceDossierEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierEntity.EvidenceDossierEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected EvidenceDossierEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.EvidenceDossierEntity(String,String,String,String,String,String)」。
    // 具体功能：「EvidenceDossierEntity.EvidenceDossierEntity(String,String,String,String,String,String)」：使用 「id」(String)、「caseId」(String)、「actorId」(String)、「summaryJson」(String)、「timelineJson」(String)、「matrixSummaryJson」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceDossierEntity.EvidenceDossierEntity(String,String,String,String,String,String)」的上游创建点包括 「EvidenceDossierEntity.firstBuild」、「EvidenceDossierEntity.collecting」、「EvidenceDossierEntity.frozen」。
    // 下游影响：「EvidenceDossierEntity.EvidenceDossierEntity(String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierEntity.EvidenceDossierEntity(String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private EvidenceDossierEntity(
            String id,
            String caseId,
            String actorId,
            String summaryJson,
            String timelineJson,
            String matrixSummaryJson) {
        super(id);
        this.caseId = caseId;
        this.dossierStatus = "BUILT";
        this.dossierVersion = 1;
        this.summaryJson = summaryJson;
        this.timelineJson = timelineJson;
        this.matrixSummaryJson = matrixSummaryJson;
        this.builtAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.firstBuild(String,String,String,String,String,String)」。
    // 具体功能：「EvidenceDossierEntity.firstBuild(String,String,String,String,String,String)」：构建首版Build，最终返回「EvidenceDossierEntity」。
    // 上游调用：「EvidenceDossierEntity.firstBuild(String,String,String,String,String,String)」的上游调用点包括 「EvidenceApplicationService.buildDossier」、「RouterApiIntegrationTest.seedCase」、「RouterApplicationServiceTest.mockCaseAndDossier」。
    // 下游影响：「EvidenceDossierEntity.firstBuild(String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidenceDossierEntity」交给调用方。
    // 系统意义：「EvidenceDossierEntity.firstBuild(String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static EvidenceDossierEntity firstBuild(
            String id,
            String caseId,
            String actorId,
            String summaryJson,
            String timelineJson,
            String matrixSummaryJson) {
        return new EvidenceDossierEntity(
                id,
                caseId,
                actorId,
                summaryJson,
                timelineJson,
                matrixSummaryJson);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.collecting(String,String,String)」。
    // 具体功能：「EvidenceDossierEntity.collecting(String,String,String)」：更新收集中：先更新内部状态 「dossierStatus」、「dossierVersion」、「builtAt」；处理的关键状态/协议值包括 「{}」、「[]」、「COLLECTING」，最终返回「EvidenceDossierEntity」。
    // 上游调用：「EvidenceDossierEntity.collecting(String,String,String)」的上游调用点包括 「EvidenceApplicationService.upload」、「EvidenceApplicationServiceTest.reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence」、「EvidenceRoomIntegrationTest.seedEvidenceCase」。
    // 下游影响：「EvidenceDossierEntity.collecting(String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidenceDossierEntity」交给调用方。
    // 系统意义：「EvidenceDossierEntity.collecting(String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static EvidenceDossierEntity collecting(
            String id, String caseId, String actorId) {
        EvidenceDossierEntity entity =
                new EvidenceDossierEntity(id, caseId, actorId, "{}", "[]", "[]");
        entity.dossierStatus = "COLLECTING";
        entity.dossierVersion = 0;
        entity.builtAt = null;
        return entity;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.frozen(String,String,int,String,String,String,String)」。
    // 具体功能：「EvidenceDossierEntity.frozen(String,String,int,String,String,String,String)」：更新冻结：先更新内部状态 「dossierStatus」、「dossierVersion」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「FROZEN」，最终返回「EvidenceDossierEntity」。
    // 上游调用：「EvidenceDossierEntity.frozen(String,String,int,String,String,String,String)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」、「EvidenceDossierRevisionService.reviseAfterRoundIfNeeded」、「EvidenceDossierRevisionService.emptyEvidenceBaseline」、「HearingCourtBootstrapService.emptyEvidenceBaseline」。
    // 下游影响：「EvidenceDossierEntity.frozen(String,String,int,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidenceDossierEntity」交给调用方。
    // 系统意义：「EvidenceDossierEntity.frozen(String,String,int,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static EvidenceDossierEntity frozen(
            String id,
            String caseId,
            int version,
            String actorId,
            String summaryJson,
            String timelineJson,
            String matrixSummaryJson) {
        if (version < 1) {
            throw new IllegalArgumentException("frozen dossier version must be positive");
        }
        EvidenceDossierEntity entity =
                new EvidenceDossierEntity(
                        id,
                        caseId,
                        actorId,
                        summaryJson,
                        timelineJson,
                        matrixSummaryJson);
        entity.dossierStatus = "FROZEN";
        entity.dossierVersion = version;
        return entity;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.rebuild(String,String,String,String)」。
    // 具体功能：「EvidenceDossierEntity.rebuild(String,String,String,String)」：更新rebuild：先更新内部状态 「dossierStatus」、「summaryJson」、「timelineJson」、「matrixSummaryJson」；处理的关键状态/协议值包括 「BUILT」，最终返回「void」。
    // 上游调用：「EvidenceDossierEntity.rebuild(String,String,String,String)」由使用「EvidenceDossierEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceDossierEntity.rebuild(String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierEntity.rebuild(String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void rebuild(
            String actorId,
            String summaryJson,
            String timelineJson,
            String matrixSummaryJson) {
        dossierVersion += 1;
        dossierStatus = "BUILT";
        this.summaryJson = summaryJson;
        this.timelineJson = timelineJson;
        this.matrixSummaryJson = matrixSummaryJson;
        builtAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.prePersist()」。
    // 具体功能：「EvidenceDossierEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「EvidenceDossierEntity.prePersist()」由使用「EvidenceDossierEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceDossierEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.preUpdate()」。
    // 具体功能：「EvidenceDossierEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「EvidenceDossierEntity.preUpdate()」由使用「EvidenceDossierEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceDossierEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.getCaseId()」。
    // 具体功能：「EvidenceDossierEntity.getCaseId()」：读取「EvidenceDossierEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceDossierEntity.getCaseId()」的上游调用点包括 「EvidenceDossierQueryService.view」。
    // 下游影响：「EvidenceDossierEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.getDossierVersion()」。
    // 具体功能：「EvidenceDossierEntity.getDossierVersion()」：读取「EvidenceDossierEntity」中的「dossierVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「EvidenceDossierEntity.getDossierVersion()」的上游调用点包括 「EvidenceDossierQueryService.view」、「ActiveCourtroomContextAssembler.evidenceDossierContext」。
    // 下游影响：「EvidenceDossierEntity.getDossierVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「EvidenceDossierEntity.getDossierVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getDossierVersion() {
        return dossierVersion;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.getDossierStatus()」。
    // 具体功能：「EvidenceDossierEntity.getDossierStatus()」：读取「EvidenceDossierEntity」中的「dossierStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceDossierEntity.getDossierStatus()」的上游调用点包括 「EvidenceDossierQueryService.view」、「ActiveCourtroomContextAssembler.evidenceDossierContext」。
    // 下游影响：「EvidenceDossierEntity.getDossierStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierEntity.getDossierStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getDossierStatus() {
        return dossierStatus;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.getSummaryJson()」。
    // 具体功能：「EvidenceDossierEntity.getSummaryJson()」：读取「EvidenceDossierEntity」中的「summaryJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceDossierEntity.getSummaryJson()」的上游调用点包括 「EvidenceDossierQueryService.view」、「ActiveCourtroomContextAssembler.evidenceDossierContext」。
    // 下游影响：「EvidenceDossierEntity.getSummaryJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierEntity.getSummaryJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getSummaryJson() {
        return summaryJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.getTimelineJson()」。
    // 具体功能：「EvidenceDossierEntity.getTimelineJson()」：读取「EvidenceDossierEntity」中的「timelineJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceDossierEntity.getTimelineJson()」的上游调用点包括 「EvidenceDossierQueryService.view」、「ActiveCourtroomContextAssembler.evidenceDossierContext」。
    // 下游影响：「EvidenceDossierEntity.getTimelineJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierEntity.getTimelineJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getTimelineJson() {
        return timelineJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvidenceDossierEntity.getMatrixSummaryJson()」。
    // 具体功能：「EvidenceDossierEntity.getMatrixSummaryJson()」：读取「EvidenceDossierEntity」中的「matrixSummaryJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceDossierEntity.getMatrixSummaryJson()」的上游调用点包括 「EvidenceDossierQueryService.view」、「ActiveCourtroomContextAssembler.evidenceDossierContext」。
    // 下游影响：「EvidenceDossierEntity.getMatrixSummaryJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierEntity.getMatrixSummaryJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getMatrixSummaryJson() {
        return matrixSummaryJson;
    }
}
