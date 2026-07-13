/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射评估链路标识数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「pending」、「retry」、「complete」、「fail」、「prePersist」、「preUpdate」；映射案件全链路实体并提供 Spring Data 仓储查询。
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

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「EvaluationTraceEntity」。
// 类型职责：映射评估链路标识数据库记录并保存可审计状态；本类型显式提供 「EvaluationTraceEntity」、「EvaluationTraceEntity」、「pending」、「retry」、「complete」、「fail」。
// 协作关系：主要由 「CaseClosureService.prepareClosure」、「CaseClosureService.reportView」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "evaluation_record")
public class EvaluationTraceEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "evaluation_version", nullable = false)
    private int evaluationVersion;

    @Column(name = "evaluation_status", length = 32, nullable = false)
    private String evaluationStatus;

    @Column(name = "evaluator_model", length = 128)
    private String evaluatorModel;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private String inputSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metric_scores_json", nullable = false, columnDefinition = "jsonb")
    private String metricScoresJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings_json", nullable = false, columnDefinition = "jsonb")
    private String findingsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", nullable = false, columnDefinition = "jsonb")
    private String reportJson;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "token_usage")
    private Integer tokenUsage;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.EvaluationTraceEntity()」。
    // 具体功能：「EvaluationTraceEntity.EvaluationTraceEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvaluationTraceEntity.EvaluationTraceEntity()」的上游创建点包括 「EvaluationTraceEntity.pending」。
    // 下游影响：「EvaluationTraceEntity.EvaluationTraceEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvaluationTraceEntity.EvaluationTraceEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected EvaluationTraceEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.EvaluationTraceEntity(String)」。
    // 具体功能：「EvaluationTraceEntity.EvaluationTraceEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvaluationTraceEntity.EvaluationTraceEntity(String)」的上游创建点包括 「EvaluationTraceEntity.pending」。
    // 下游影响：「EvaluationTraceEntity.EvaluationTraceEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvaluationTraceEntity.EvaluationTraceEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private EvaluationTraceEntity(String id) {
        super(id);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.pending(String,String,int,String,String)」。
    // 具体功能：「EvaluationTraceEntity.pending(String,String,int,String,String)」：更新待处理：先更新内部状态 「caseId」、「evaluationVersion」、「evaluationStatus」、「inputSnapshotJson」；处理的关键状态/协议值包括 「PENDING」、「{}」、「[]」，最终返回「EvaluationTraceEntity」。
    // 上游调用：「EvaluationTraceEntity.pending(String,String,int,String,String)」的上游调用点包括 「CaseClosureService.prepareClosure」。
    // 下游影响：「EvaluationTraceEntity.pending(String,String,int,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvaluationTraceEntity」交给调用方。
    // 系统意义：「EvaluationTraceEntity.pending(String,String,int,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static EvaluationTraceEntity pending(
            String id,
            String caseId,
            int version,
            String inputSnapshotJson,
            String actorId) {
        EvaluationTraceEntity trace = new EvaluationTraceEntity(id);
        trace.caseId = caseId;
        trace.evaluationVersion = version;
        trace.evaluationStatus = "PENDING";
        trace.inputSnapshotJson = inputSnapshotJson;
        trace.metricScoresJson = "{}";
        trace.findingsJson = "[]";
        trace.reportJson = "{}";
        trace.createdBy = actorId;
        trace.updatedBy = actorId;
        return trace;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.retry(String,String)」。
    // 具体功能：「EvaluationTraceEntity.retry(String,String)」：重试评估链路标识：先更新内部状态 「evaluationStatus」、「inputSnapshotJson」、「evaluatorModel」、「promptVersion」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「COMPLETED」、「PENDING」、「{}」、「[]」，最终返回「void」。
    // 上游调用：「EvaluationTraceEntity.retry(String,String)」由使用「EvaluationTraceEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvaluationTraceEntity.retry(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvaluationTraceEntity.retry(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void retry(String inputSnapshotJson, String actorId) {
        if ("COMPLETED".equals(evaluationStatus)) {
            throw new IllegalStateException(
                    "completed evaluation cannot be retried");
        }
        evaluationStatus = "PENDING";
        this.inputSnapshotJson = inputSnapshotJson;
        evaluatorModel = null;
        promptVersion = null;
        metricScoresJson = "{}";
        findingsJson = "[]";
        reportJson = "{}";
        latencyMs = null;
        tokenUsage = null;
        completedAt = null;
        updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.complete(String,String,String,String,String,long,int,String)」。
    // 具体功能：「EvaluationTraceEntity.complete(String,String,String,String,String,long,int,String)」：完成评估链路标识：先更新内部状态 「evaluationStatus」、「evaluatorModel」、「promptVersion」、「metricScoresJson」；处理的关键状态/协议值包括 「COMPLETED」，最终返回「void」。
    // 上游调用：「EvaluationTraceEntity.complete(String,String,String,String,String,long,int,String)」由使用「EvaluationTraceEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvaluationTraceEntity.complete(String,String,String,String,String,long,int,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvaluationTraceEntity.complete(String,String,String,String,String,long,int,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void complete(
            String evaluatorModel,
            String promptVersion,
            String metricScoresJson,
            String findingsJson,
            String reportJson,
            long latencyMs,
            int tokenUsage,
            String actorId) {
        this.evaluationStatus = "COMPLETED";
        this.evaluatorModel = evaluatorModel;
        this.promptVersion = promptVersion;
        this.metricScoresJson = metricScoresJson;
        this.findingsJson = findingsJson;
        this.reportJson = reportJson;
        this.latencyMs = latencyMs;
        this.tokenUsage = tokenUsage;
        this.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.fail(String,String)」。
    // 具体功能：「EvaluationTraceEntity.fail(String,String)」：标记失败评估链路标识：先更新内部状态 「evaluationStatus」、「reportJson」、「completedAt」、「updatedBy」；处理的关键状态/协议值包括 「FAILED」，最终返回「void」。
    // 上游调用：「EvaluationTraceEntity.fail(String,String)」由使用「EvaluationTraceEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvaluationTraceEntity.fail(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvaluationTraceEntity.fail(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void fail(String reportJson, String actorId) {
        evaluationStatus = "FAILED";
        this.reportJson = reportJson;
        completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.prePersist()」。
    // 具体功能：「EvaluationTraceEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「EvaluationTraceEntity.prePersist()」由使用「EvaluationTraceEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvaluationTraceEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvaluationTraceEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.preUpdate()」。
    // 具体功能：「EvaluationTraceEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「EvaluationTraceEntity.preUpdate()」由使用「EvaluationTraceEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvaluationTraceEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvaluationTraceEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getCaseId()」。
    // 具体功能：「EvaluationTraceEntity.getCaseId()」：读取「EvaluationTraceEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvaluationTraceEntity.getCaseId()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getEvaluationVersion()」。
    // 具体功能：「EvaluationTraceEntity.getEvaluationVersion()」：读取「EvaluationTraceEntity」中的「evaluationVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「EvaluationTraceEntity.getEvaluationVersion()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getEvaluationVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getEvaluationVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getEvaluationVersion() {
        return evaluationVersion;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getEvaluationStatus()」。
    // 具体功能：「EvaluationTraceEntity.getEvaluationStatus()」：读取「EvaluationTraceEntity」中的「evaluationStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvaluationTraceEntity.getEvaluationStatus()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getEvaluationStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getEvaluationStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getEvaluationStatus() {
        return evaluationStatus;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getEvaluatorModel()」。
    // 具体功能：「EvaluationTraceEntity.getEvaluatorModel()」：读取「EvaluationTraceEntity」中的「evaluatorModel」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvaluationTraceEntity.getEvaluatorModel()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getEvaluatorModel()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getEvaluatorModel()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getEvaluatorModel() {
        return evaluatorModel;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getPromptVersion()」。
    // 具体功能：「EvaluationTraceEntity.getPromptVersion()」：读取「EvaluationTraceEntity」中的「promptVersion」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvaluationTraceEntity.getPromptVersion()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getPromptVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getPromptVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPromptVersion() {
        return promptVersion;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getInputSnapshotJson()」。
    // 具体功能：「EvaluationTraceEntity.getInputSnapshotJson()」：读取「EvaluationTraceEntity」中的「inputSnapshotJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvaluationTraceEntity.getInputSnapshotJson()」由使用「EvaluationTraceEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvaluationTraceEntity.getInputSnapshotJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getInputSnapshotJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getInputSnapshotJson() {
        return inputSnapshotJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getMetricScoresJson()」。
    // 具体功能：「EvaluationTraceEntity.getMetricScoresJson()」：读取「EvaluationTraceEntity」中的「metricScoresJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvaluationTraceEntity.getMetricScoresJson()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getMetricScoresJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getMetricScoresJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getMetricScoresJson() {
        return metricScoresJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getFindingsJson()」。
    // 具体功能：「EvaluationTraceEntity.getFindingsJson()」：读取「EvaluationTraceEntity」中的「findingsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvaluationTraceEntity.getFindingsJson()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getFindingsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getFindingsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getFindingsJson() {
        return findingsJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getReportJson()」。
    // 具体功能：「EvaluationTraceEntity.getReportJson()」：读取「EvaluationTraceEntity」中的「reportJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvaluationTraceEntity.getReportJson()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getReportJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getReportJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getReportJson() {
        return reportJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getLatencyMs()」。
    // 具体功能：「EvaluationTraceEntity.getLatencyMs()」：读取「EvaluationTraceEntity」中的「latencyMs」状态，向 JPA、应用服务或序列化层返回「Long」。
    // 上游调用：「EvaluationTraceEntity.getLatencyMs()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getLatencyMs()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Long」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getLatencyMs()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public Long getLatencyMs() {
        return latencyMs;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getTokenUsage()」。
    // 具体功能：「EvaluationTraceEntity.getTokenUsage()」：读取「EvaluationTraceEntity」中的「tokenUsage」状态，向 JPA、应用服务或序列化层返回「Integer」。
    // 上游调用：「EvaluationTraceEntity.getTokenUsage()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getTokenUsage()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Integer」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getTokenUsage()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public Integer getTokenUsage() {
        return tokenUsage;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getCompletedAt()」。
    // 具体功能：「EvaluationTraceEntity.getCompletedAt()」：读取「EvaluationTraceEntity」中的「completedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「EvaluationTraceEntity.getCompletedAt()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getCompletedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getCompletedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「EvaluationTraceEntity.getCreatedAt()」。
    // 具体功能：「EvaluationTraceEntity.getCreatedAt()」：读取「EvaluationTraceEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「EvaluationTraceEntity.getCreatedAt()」的上游调用点包括 「CaseClosureService.reportView」。
    // 下游影响：「EvaluationTraceEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「EvaluationTraceEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
