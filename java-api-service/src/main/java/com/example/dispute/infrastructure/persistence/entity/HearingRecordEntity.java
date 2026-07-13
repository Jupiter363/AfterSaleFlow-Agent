/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射庭审记录数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「record」、「prePersist」、「getNodeName」、「getRoundNo」、「getRecordType」、「getOutputJson」；映射案件全链路实体并提供 Spring Data 仓储查询。
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

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「HearingRecordEntity」。
// 类型职责：映射庭审记录数据库记录并保存可审计状态；本类型显式提供 「HearingRecordEntity」、「HearingRecordEntity」、「record」、「prePersist」、「getNodeName」、「getRoundNo」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「HearingCourtBootstrapService.recordSnapshotIfAbsent」、「ActiveCourtroomContextAssemblerTest.bootstrapSnapshot」、「HearingCourtOrchestratorTest.bootstrapSnapshot」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "hearing_stage_record")
public class HearingRecordEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "hearing_state_id", length = 64, nullable = false)
    private String hearingStateId;
    @Column(name = "workflow_id", length = 128, nullable = false)
    private String workflowId;
    @Column(name = "node_name", length = 64, nullable = false)
    private String nodeName;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Column(name = "record_type", length = 64, nullable = false)
    private String recordType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", nullable = false, columnDefinition = "jsonb")
    private String inputJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", nullable = false, columnDefinition = "jsonb")
    private String outputJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;
    @Column(name = "prompt_version", length = 64)
    private String promptVersion;
    @Column(name = "model", length = 128)
    private String model;
    @Column(name = "latency_ms")
    private Long latencyMs;
    @Column(name = "token_usage")
    private Integer tokenUsage;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingRecordEntity.HearingRecordEntity()」。
    // 具体功能：「HearingRecordEntity.HearingRecordEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「HearingRecordEntity.HearingRecordEntity()」的上游创建点包括 「HearingRecordEntity.record」。
    // 下游影响：「HearingRecordEntity.HearingRecordEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRecordEntity.HearingRecordEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected HearingRecordEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingRecordEntity.HearingRecordEntity(String)」。
    // 具体功能：「HearingRecordEntity.HearingRecordEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「HearingRecordEntity.HearingRecordEntity(String)」的上游创建点包括 「HearingRecordEntity.record」。
    // 下游影响：「HearingRecordEntity.HearingRecordEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRecordEntity.HearingRecordEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private HearingRecordEntity(String id) { super(id); }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingRecordEntity.record(String,String,String,String,String,int,String,String,String,String,String,String,Long,Integer,String)」。
    // 具体功能：「HearingRecordEntity.record(String,String,String,String,String,int,String,String,String,String,String,String,Long,Integer,String)」：记录庭审记录：先更新内部状态 「caseId」、「hearingStateId」、「workflowId」、「nodeName」，最终返回「HearingRecordEntity」。
    // 上游调用：「HearingRecordEntity.record(String,String,String,String,String,int,String,String,String,String,String,String,Long,Integer,String)」的上游调用点包括 「HearingCourtBootstrapService.recordSnapshotIfAbsent」、「CaseFulfillmentDisputeActivitiesImpl.persistAnalysis」、「ActiveCourtroomContextAssemblerTest.bootstrapSnapshot」、「HearingCourtOrchestratorTest.bootstrapSnapshot」。
    // 下游影响：「HearingRecordEntity.record(String,String,String,String,String,int,String,String,String,String,String,String,Long,Integer,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HearingRecordEntity」交给调用方。
    // 系统意义：「HearingRecordEntity.record(String,String,String,String,String,int,String,String,String,String,String,String,Long,Integer,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static HearingRecordEntity record(
            String id, String caseId, String hearingStateId, String workflowId,
            String nodeName, int roundNo, String recordType, String inputJson,
            String outputJson, String metadataJson, String promptVersion,
            String model, Long latencyMs, Integer tokenUsage, String actorId) {
        HearingRecordEntity record = new HearingRecordEntity(id);
        record.caseId = caseId;
        record.hearingStateId = hearingStateId;
        record.workflowId = workflowId;
        record.nodeName = nodeName;
        record.roundNo = roundNo;
        record.recordType = recordType;
        record.inputJson = inputJson;
        record.outputJson = outputJson;
        record.metadataJson = metadataJson;
        record.promptVersion = promptVersion;
        record.model = model;
        record.latencyMs = latencyMs;
        record.tokenUsage = tokenUsage;
        record.createdBy = actorId;
        return record;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingRecordEntity.prePersist()」。
    // 具体功能：「HearingRecordEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「HearingRecordEntity.prePersist()」由使用「HearingRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingRecordEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRecordEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() { createdAt = OffsetDateTime.now(ZoneOffset.UTC); }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingRecordEntity.getNodeName()」。
    // 具体功能：「HearingRecordEntity.getNodeName()」：读取「HearingRecordEntity」中的「nodeName」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingRecordEntity.getNodeName()」由使用「HearingRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingRecordEntity.getNodeName()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingRecordEntity.getNodeName()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getNodeName() { return nodeName; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingRecordEntity.getRoundNo()」。
    // 具体功能：「HearingRecordEntity.getRoundNo()」：读取「HearingRecordEntity」中的「roundNo」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「HearingRecordEntity.getRoundNo()」由使用「HearingRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingRecordEntity.getRoundNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「HearingRecordEntity.getRoundNo()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getRoundNo() { return roundNo; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingRecordEntity.getRecordType()」。
    // 具体功能：「HearingRecordEntity.getRecordType()」：读取「HearingRecordEntity」中的「recordType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingRecordEntity.getRecordType()」由使用「HearingRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingRecordEntity.getRecordType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingRecordEntity.getRecordType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRecordType() { return recordType; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingRecordEntity.getOutputJson()」。
    // 具体功能：「HearingRecordEntity.getOutputJson()」：读取「HearingRecordEntity」中的「outputJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingRecordEntity.getOutputJson()」由使用「HearingRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingRecordEntity.getOutputJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingRecordEntity.getOutputJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getOutputJson() { return outputJson; }
}
