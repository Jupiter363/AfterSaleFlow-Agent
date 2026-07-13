/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射审计Log数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「caseCreated」、「record」、「prePersist」、「getAction」、「getTraceId」、「getCaseId」；映射案件全链路实体并提供 Spring Data 仓储查询。
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

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「AuditLogEntity」。
// 类型职责：映射审计Log数据库记录并保存可审计状态；本类型显式提供 「AuditLogEntity」、「AuditLogEntity」、「caseCreated」、「record」、「prePersist」、「getAction」。
// 协作关系：主要由 「AuditQueryService.toView」、「AuditRecorder.record」、「CaseApplicationService.createNew」、「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "audit_log")
public class AuditLogEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "trace_id", length = 128, nullable = false)
    private String traceId;

    @Column(name = "request_id", length = 128, nullable = false)
    private String requestId;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "role", length = 32, nullable = false)
    private String role;

    @Column(name = "service", length = 64, nullable = false)
    private String service;

    @Column(name = "action", length = 128, nullable = false)
    private String action;

    @Column(name = "resource_type", length = 64, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "outcome", length = 32, nullable = false)
    private String outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", nullable = false, columnDefinition = "jsonb")
    private String beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", nullable = false, columnDefinition = "jsonb")
    private String afterJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.AuditLogEntity()」。
    // 具体功能：「AuditLogEntity.AuditLogEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AuditLogEntity.AuditLogEntity()」的上游创建点包括 「AuditLogEntity.caseCreated」、「AuditLogEntity.record」。
    // 下游影响：「AuditLogEntity.AuditLogEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AuditLogEntity.AuditLogEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected AuditLogEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.AuditLogEntity(String,String,String,String,String,String,String,String,String,String,String)」。
    // 具体功能：「AuditLogEntity.AuditLogEntity(String,String,String,String,String,String,String,String,String,String,String)」：使用 「id」(String)、「caseId」(String)、「traceId」(String)、「requestId」(String)、「userId」(String)、「role」(String)、「action」(String)、「resourceType」(String)、「resourceId」(String)、「beforeJson」(String)、「afterJson」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AuditLogEntity.AuditLogEntity(String,String,String,String,String,String,String,String,String,String,String)」的上游创建点包括 「AuditLogEntity.caseCreated」、「AuditLogEntity.record」。
    // 下游影响：「AuditLogEntity.AuditLogEntity(String,String,String,String,String,String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AuditLogEntity.AuditLogEntity(String,String,String,String,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private AuditLogEntity(
            String id,
            String caseId,
            String traceId,
            String requestId,
            String userId,
            String role,
            String action,
            String resourceType,
            String resourceId,
            String beforeJson,
            String afterJson) {
        super(id);
        this.caseId = caseId;
        this.traceId = traceId;
        this.requestId = requestId;
        this.userId = userId;
        this.role = role;
        this.service = "java-api-service";
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.outcome = "SUCCESS";
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.metadataJson = "{}";
        this.createdBy = userId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.caseCreated(String,String,String,String,String,String,String)」。
    // 具体功能：「AuditLogEntity.caseCreated(String,String,String,String,String,String,String)」：构建案件Created；处理的关键状态/协议值包括 「CASE_CREATED」、「FULFILLMENT_CASE」、「{}」，最终返回「AuditLogEntity」。
    // 上游调用：「AuditLogEntity.caseCreated(String,String,String,String,String,String,String)」的上游调用点包括 「CaseApplicationService.createNew」。
    // 下游影响：「AuditLogEntity.caseCreated(String,String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「AuditLogEntity」交给调用方。
    // 系统意义：「AuditLogEntity.caseCreated(String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static AuditLogEntity caseCreated(
            String id,
            String caseId,
            String traceId,
            String requestId,
            String userId,
            String role,
            String afterJson) {
        return new AuditLogEntity(
                id,
                caseId,
                traceId,
                requestId,
                userId,
                role,
                "CASE_CREATED",
                "FULFILLMENT_CASE",
                caseId,
                "{}",
                afterJson);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.record(String,String,String,String,String,String,String,String,String,String,String)」。
    // 具体功能：「AuditLogEntity.record(String,String,String,String,String,String,String,String,String,String,String)」：记录审计Log，最终返回「AuditLogEntity」。
    // 上游调用：「AuditLogEntity.record(String,String,String,String,String,String,String,String,String,String,String)」的上游调用点包括 「AuditRecorder.record」、「AuditQueryServiceTest.reviewerCanReadStructuredCaseAuditLogs」。
    // 下游影响：「AuditLogEntity.record(String,String,String,String,String,String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「AuditLogEntity」交给调用方。
    // 系统意义：「AuditLogEntity.record(String,String,String,String,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static AuditLogEntity record(
            String id,
            String caseId,
            String traceId,
            String requestId,
            String userId,
            String role,
            String action,
            String resourceType,
            String resourceId,
            String beforeJson,
            String afterJson) {
        return new AuditLogEntity(
                id,
                caseId,
                traceId,
                requestId,
                userId,
                role,
                action,
                resourceType,
                resourceId,
                beforeJson,
                afterJson);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.prePersist()」。
    // 具体功能：「AuditLogEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「AuditLogEntity.prePersist()」由使用「AuditLogEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AuditLogEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AuditLogEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getAction()」。
    // 具体功能：「AuditLogEntity.getAction()」：读取「AuditLogEntity」中的「action」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getAction()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getAction()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getAction()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getAction() {
        return action;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getTraceId()」。
    // 具体功能：「AuditLogEntity.getTraceId()」：读取「AuditLogEntity」中的「traceId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getTraceId()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getTraceId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getTraceId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getTraceId() {
        return traceId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getCaseId()」。
    // 具体功能：「AuditLogEntity.getCaseId()」：读取「AuditLogEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getCaseId()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getRequestId()」。
    // 具体功能：「AuditLogEntity.getRequestId()」：读取「AuditLogEntity」中的「requestId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getRequestId()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getRequestId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getRequestId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRequestId() {
        return requestId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getUserId()」。
    // 具体功能：「AuditLogEntity.getUserId()」：读取「AuditLogEntity」中的「userId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getUserId()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getUserId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getUserId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getUserId() {
        return userId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getRole()」。
    // 具体功能：「AuditLogEntity.getRole()」：读取「AuditLogEntity」中的「role」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getRole()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getRole()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRole() {
        return role;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getService()」。
    // 具体功能：「AuditLogEntity.getService()」：读取「AuditLogEntity」中的「service」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getService()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getService()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getService()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getService() {
        return service;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getResourceType()」。
    // 具体功能：「AuditLogEntity.getResourceType()」：读取「AuditLogEntity」中的「resourceType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getResourceType()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getResourceType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getResourceType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getResourceType() {
        return resourceType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getResourceId()」。
    // 具体功能：「AuditLogEntity.getResourceId()」：读取「AuditLogEntity」中的「resourceId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getResourceId()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getResourceId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getResourceId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getResourceId() {
        return resourceId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getOutcome()」。
    // 具体功能：「AuditLogEntity.getOutcome()」：读取「AuditLogEntity」中的「outcome」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getOutcome()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getOutcome()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getOutcome()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getOutcome() {
        return outcome;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getBeforeJson()」。
    // 具体功能：「AuditLogEntity.getBeforeJson()」：读取「AuditLogEntity」中的「beforeJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getBeforeJson()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getBeforeJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getBeforeJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getBeforeJson() {
        return beforeJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getAfterJson()」。
    // 具体功能：「AuditLogEntity.getAfterJson()」：读取「AuditLogEntity」中的「afterJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getAfterJson()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getAfterJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getAfterJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getAfterJson() {
        return afterJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getMetadataJson()」。
    // 具体功能：「AuditLogEntity.getMetadataJson()」：读取「AuditLogEntity」中的「metadataJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AuditLogEntity.getMetadataJson()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getMetadataJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AuditLogEntity.getMetadataJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getMetadataJson() {
        return metadataJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AuditLogEntity.getCreatedAt()」。
    // 具体功能：「AuditLogEntity.getCreatedAt()」：读取「AuditLogEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「AuditLogEntity.getCreatedAt()」的上游调用点包括 「AuditQueryService.toView」。
    // 下游影响：「AuditLogEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「AuditLogEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
