/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射评议Report数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「record」、「prePersist」、「getReportVersion」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「DeliberationReportEntity」。
// 类型职责：映射评议Report数据库记录并保存可审计状态；本类型显式提供 「DeliberationReportEntity」、「DeliberationReportEntity」、「record」、「prePersist」、「getReportVersion」。
// 协作关系：主要由 「FinalWorkflowActivitiesAdapter.persistReport」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "deliberation_report")
public class DeliberationReportEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "report_version", nullable = false)
    private int reportVersion;

    @Column(name = "draft_id", length = 64, nullable = false)
    private String draftId;

    @Column(name = "frozen_dossier_version", nullable = false)
    private int frozenDossierVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "panel_result_json", nullable = false, columnDefinition = "jsonb")
    private String panelResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "major_risks_json", nullable = false, columnDefinition = "jsonb")
    private String majorRisksJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consensus_json", nullable = false, columnDefinition = "jsonb")
    private String consensusJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "disagreements_json", nullable = false, columnDefinition = "jsonb")
    private String disagreementsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "recommended_revision_json",
            nullable = false,
            columnDefinition = "jsonb")
    private String recommendedRevisionJson;

    @Column(name = "trace_id", length = 128, nullable = false)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「DeliberationReportEntity.DeliberationReportEntity()」。
    // 具体功能：「DeliberationReportEntity.DeliberationReportEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「DeliberationReportEntity.DeliberationReportEntity()」的上游创建点包括 「DeliberationReportEntity.record」。
    // 下游影响：「DeliberationReportEntity.DeliberationReportEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DeliberationReportEntity.DeliberationReportEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected DeliberationReportEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「DeliberationReportEntity.DeliberationReportEntity(String)」。
    // 具体功能：「DeliberationReportEntity.DeliberationReportEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「DeliberationReportEntity.DeliberationReportEntity(String)」的上游创建点包括 「DeliberationReportEntity.record」。
    // 下游影响：「DeliberationReportEntity.DeliberationReportEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DeliberationReportEntity.DeliberationReportEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private DeliberationReportEntity(String id) {
        super(id);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「DeliberationReportEntity.record(String,String,int,String,int,String,String,String,String,String,String,String)」。
    // 具体功能：「DeliberationReportEntity.record(String,String,int,String,int,String,String,String,String,String,String,String)」：记录评议Report：先更新内部状态 「caseId」、「reportVersion」、「draftId」、「frozenDossierVersion」，最终返回「DeliberationReportEntity」。
    // 上游调用：「DeliberationReportEntity.record(String,String,int,String,int,String,String,String,String,String,String,String)」的上游调用点包括 「FinalWorkflowActivitiesAdapter.persistReport」。
    // 下游影响：「DeliberationReportEntity.record(String,String,int,String,int,String,String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「DeliberationReportEntity」交给调用方。
    // 系统意义：「DeliberationReportEntity.record(String,String,int,String,int,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static DeliberationReportEntity record(
            String id,
            String caseId,
            int version,
            String draftId,
            int dossierVersion,
            String panelResultJson,
            String majorRisksJson,
            String consensusJson,
            String disagreementsJson,
            String recommendedRevisionJson,
            String traceId,
            String actorId) {
        DeliberationReportEntity report = new DeliberationReportEntity(id);
        report.caseId = caseId;
        report.reportVersion = version;
        report.draftId = draftId;
        report.frozenDossierVersion = dossierVersion;
        report.panelResultJson = panelResultJson;
        report.majorRisksJson = majorRisksJson;
        report.consensusJson = consensusJson;
        report.disagreementsJson = disagreementsJson;
        report.recommendedRevisionJson = recommendedRevisionJson;
        report.traceId = traceId;
        report.createdBy = actorId;
        return report;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「DeliberationReportEntity.prePersist()」。
    // 具体功能：「DeliberationReportEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「DeliberationReportEntity.prePersist()」由使用「DeliberationReportEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「DeliberationReportEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DeliberationReportEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「DeliberationReportEntity.getReportVersion()」。
    // 具体功能：「DeliberationReportEntity.getReportVersion()」：读取「DeliberationReportEntity」中的「reportVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「DeliberationReportEntity.getReportVersion()」由使用「DeliberationReportEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「DeliberationReportEntity.getReportVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「DeliberationReportEntity.getReportVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getReportVersion() {
        return reportVersion;
    }
}
