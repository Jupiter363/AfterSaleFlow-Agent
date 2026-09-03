/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射路由决定数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「record」、「prePersist」、「getCaseId」、「getIdempotencyKey」、「getRouteType」、「getReasonCode」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.RouteType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「RouteDecisionEntity」。
// 类型职责：映射路由决定数据库记录并保存可审计状态；本类型显式提供 「RouteDecisionEntity」、「RouteDecisionEntity」、「record」、「prePersist」、「required」、「getCaseId」。
// 协作关系：主要由 「RouterApplicationService.createConclusion」、「RouterApplicationService.route」、「RouterApplicationService.toView」、「RemedyApplicationServiceIntegrationTest.seedFlow」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "route_decision")
public class RouteDecisionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false, unique = true)
    private String caseId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "route_type", length = 64, nullable = false)
    private RouteType routeType;

    @Column(name = "reason_code", length = 128, nullable = false)
    private String reasonCode;

    @Column(name = "reason_detail", nullable = false, columnDefinition = "text")
    private String reasonDetail;

    @Column(name = "requires_additional_evidence", nullable = false)
    private boolean requiresAdditionalEvidence;

    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;

    @Column(name = "policy_rule_id", length = 64)
    private String policyRuleId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private String inputSnapshotJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.RouteDecisionEntity()」。
    // 具体功能：「RouteDecisionEntity.RouteDecisionEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「RouteDecisionEntity.RouteDecisionEntity()」的上游创建点包括 「RouteDecisionEntity.record」。
    // 下游影响：「RouteDecisionEntity.RouteDecisionEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RouteDecisionEntity.RouteDecisionEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected RouteDecisionEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.RouteDecisionEntity(String,String,String,RouteType,String,String,boolean,int,String,String,String)」。
    // 具体功能：「RouteDecisionEntity.RouteDecisionEntity(String,String,String,RouteType,String,String,boolean,int,String,String,String)」：使用 「id」(String)、「caseId」(String)、「idempotencyKey」(String)、「routeType」(RouteType)、「reasonCode」(String)、「reasonDetail」(String)、「requiresAdditionalEvidence」(boolean)、「dossierVersion」(int)、「policyRuleId」(String)、「inputSnapshotJson」(String)、「actorId」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「RouteDecisionEntity.RouteDecisionEntity(String,String,String,RouteType,String,String,boolean,int,String,String,String)」的上游创建点包括 「RouteDecisionEntity.record」。
    // 下游影响：「RouteDecisionEntity.RouteDecisionEntity(String,String,String,RouteType,String,String,boolean,int,String,String,String)」向下依次触达 「Objects.requireNonNull」、「required」。
    // 系统意义：「RouteDecisionEntity.RouteDecisionEntity(String,String,String,RouteType,String,String,boolean,int,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private RouteDecisionEntity(
            String id,
            String caseId,
            String idempotencyKey,
            RouteType routeType,
            String reasonCode,
            String reasonDetail,
            boolean requiresAdditionalEvidence,
            int dossierVersion,
            String policyRuleId,
            String inputSnapshotJson,
            String actorId) {
        super(id);
        this.caseId = required(caseId, "caseId");
        this.idempotencyKey = required(idempotencyKey, "idempotencyKey");
        this.routeType = Objects.requireNonNull(routeType, "routeType must not be null");
        this.reasonCode = required(reasonCode, "reasonCode");
        this.reasonDetail = required(reasonDetail, "reasonDetail");
        this.requiresAdditionalEvidence = requiresAdditionalEvidence;
        this.dossierVersion = dossierVersion;
        this.policyRuleId = policyRuleId;
        this.inputSnapshotJson = required(inputSnapshotJson, "inputSnapshotJson");
        this.createdBy = required(actorId, "actorId");
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.record(String,String,String,RouteType,String,String,boolean,int,String,String,String)」。
    // 具体功能：「RouteDecisionEntity.record(String,String,String,RouteType,String,String,boolean,int,String,String,String)」：记录路由决定，最终返回「RouteDecisionEntity」。
    // 上游调用：「RouteDecisionEntity.record(String,String,String,RouteType,String,String,boolean,int,String,String,String)」的上游调用点包括 「RouterApplicationService.route」、「RemedyApplicationServiceIntegrationTest.seedFlow」。
    // 下游影响：「RouteDecisionEntity.record(String,String,String,RouteType,String,String,boolean,int,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RouteDecisionEntity」交给调用方。
    // 系统意义：「RouteDecisionEntity.record(String,String,String,RouteType,String,String,boolean,int,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static RouteDecisionEntity record(
            String id,
            String caseId,
            String idempotencyKey,
            RouteType routeType,
            String reasonCode,
            String reasonDetail,
            boolean requiresAdditionalEvidence,
            int dossierVersion,
            String policyRuleId,
            String inputSnapshotJson,
            String actorId) {
        return new RouteDecisionEntity(
                id,
                caseId,
                idempotencyKey,
                routeType,
                reasonCode,
                reasonDetail,
                requiresAdditionalEvidence,
                dossierVersion,
                policyRuleId,
                inputSnapshotJson,
                actorId);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.prePersist()」。
    // 具体功能：「RouteDecisionEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「RouteDecisionEntity.prePersist()」由使用「RouteDecisionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RouteDecisionEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RouteDecisionEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.required(String,String)」。
    // 具体功能：「RouteDecisionEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「RouteDecisionEntity.required(String,String)」的上游调用点包括 「RouteDecisionEntity.RouteDecisionEntity」。
    // 下游影响：「RouteDecisionEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RouteDecisionEntity.required(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.getCaseId()」。
    // 具体功能：「RouteDecisionEntity.getCaseId()」：读取「RouteDecisionEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RouteDecisionEntity.getCaseId()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「RouteDecisionEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RouteDecisionEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.getIdempotencyKey()」。
    // 具体功能：「RouteDecisionEntity.getIdempotencyKey()」：读取「RouteDecisionEntity」中的「idempotencyKey」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RouteDecisionEntity.getIdempotencyKey()」由使用「RouteDecisionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RouteDecisionEntity.getIdempotencyKey()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RouteDecisionEntity.getIdempotencyKey()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.getRouteType()」。
    // 具体功能：「RouteDecisionEntity.getRouteType()」：读取「RouteDecisionEntity」中的「routeType」状态，向 JPA、应用服务或序列化层返回「RouteType」。
    // 上游调用：「RouteDecisionEntity.getRouteType()」的上游调用点包括 「RouterApplicationService.createConclusion」、「RouterApplicationService.toView」。
    // 下游影响：「RouteDecisionEntity.getRouteType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RouteType」交给调用方。
    // 系统意义：「RouteDecisionEntity.getRouteType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public RouteType getRouteType() {
        return routeType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.getReasonCode()」。
    // 具体功能：「RouteDecisionEntity.getReasonCode()」：读取「RouteDecisionEntity」中的「reasonCode」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RouteDecisionEntity.getReasonCode()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「RouteDecisionEntity.getReasonCode()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RouteDecisionEntity.getReasonCode()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getReasonCode() {
        return reasonCode;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.getReasonDetail()」。
    // 具体功能：「RouteDecisionEntity.getReasonDetail()」：读取「RouteDecisionEntity」中的「reasonDetail」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RouteDecisionEntity.getReasonDetail()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「RouteDecisionEntity.getReasonDetail()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RouteDecisionEntity.getReasonDetail()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getReasonDetail() {
        return reasonDetail;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.isRequiresAdditionalEvidence()」。
    // 具体功能：「RouteDecisionEntity.isRequiresAdditionalEvidence()」：判断是否RequiresAdditional证据，最终返回「boolean」。
    // 上游调用：「RouteDecisionEntity.isRequiresAdditionalEvidence()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「RouteDecisionEntity.isRequiresAdditionalEvidence()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「RouteDecisionEntity.isRequiresAdditionalEvidence()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public boolean isRequiresAdditionalEvidence() {
        return requiresAdditionalEvidence;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.getDossierVersion()」。
    // 具体功能：「RouteDecisionEntity.getDossierVersion()」：读取「RouteDecisionEntity」中的「dossierVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「RouteDecisionEntity.getDossierVersion()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「RouteDecisionEntity.getDossierVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「RouteDecisionEntity.getDossierVersion()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getDossierVersion() {
        return dossierVersion;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.getPolicyRuleId()」。
    // 具体功能：「RouteDecisionEntity.getPolicyRuleId()」：读取「RouteDecisionEntity」中的「policyRuleId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RouteDecisionEntity.getPolicyRuleId()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「RouteDecisionEntity.getPolicyRuleId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RouteDecisionEntity.getPolicyRuleId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPolicyRuleId() {
        return policyRuleId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「RouteDecisionEntity.getCreatedAt()」。
    // 具体功能：「RouteDecisionEntity.getCreatedAt()」：读取「RouteDecisionEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「RouteDecisionEntity.getCreatedAt()」的上游调用点包括 「RouterApplicationService.toView」。
    // 下游影响：「RouteDecisionEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「RouteDecisionEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
