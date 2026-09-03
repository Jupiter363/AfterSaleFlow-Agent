/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射动作记录数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「running」、「runningGoverned」、「retry」、「succeed」、「fail」、「prePersist」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
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

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「ActionRecordEntity」。
// 类型职责：映射动作记录数据库记录并保存可审计状态；本类型显式提供 「ActionRecordEntity」、「ActionRecordEntity」、「running」、「runningGoverned」、「retry」、「succeed」。
// 协作关系：主要由 「ToolExecutorService.assertRecordMatches」、「ToolExecutorService.auditStarted」、「ToolExecutorService.prepare」、「ToolExecutorService.view」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "action_record")
public class ActionRecordEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "plan_id", length = 64, nullable = false)
    private String planId;

    @Column(name = "approval_record_id", length = 64, nullable = false)
    private String approvalRecordId;

    @Column(name = "action_type", length = 64, nullable = false)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32, nullable = false)
    private RiskLevel riskLevel;

    @Column(name = "idempotency_key", length = 128, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "approved_by", length = 128, nullable = false)
    private String approvedBy;

    @Column(name = "executed_by", length = 128, nullable = false)
    private String executedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
    private String requestJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
    private String resultJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", length = 32, nullable = false)
    private ExecutionStatus executionStatus;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "execution_time")
    private OffsetDateTime executionTime;

    @Column(name = "review_packet_id", length = 64)
    private String reviewPacketId;

    @Column(name = "action_snapshot_hash", length = 128)
    private String actionSnapshotHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceRefsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_refs_json", nullable = false, columnDefinition = "jsonb")
    private String ruleRefsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_run_refs_json", nullable = false, columnDefinition = "jsonb")
    private String agentRunRefsJson;

    @Column(name = "external_result_ref", length = 256)
    private String externalResultRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.ActionRecordEntity()」。
    // 具体功能：「ActionRecordEntity.ActionRecordEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ActionRecordEntity.ActionRecordEntity()」的上游创建点包括 「ActionRecordEntity.running」。
    // 下游影响：「ActionRecordEntity.ActionRecordEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ActionRecordEntity.ActionRecordEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected ActionRecordEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.ActionRecordEntity(String)」。
    // 具体功能：「ActionRecordEntity.ActionRecordEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「ActionRecordEntity.ActionRecordEntity(String)」的上游创建点包括 「ActionRecordEntity.running」。
    // 下游影响：「ActionRecordEntity.ActionRecordEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ActionRecordEntity.ActionRecordEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private ActionRecordEntity(String id) {
        super(id);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.running(String,String,String,String,String,RiskLevel,String,String,String,String)」。
    // 具体功能：「ActionRecordEntity.running(String,String,String,String,String,RiskLevel,String,String,String,String)」：运行执行中：先更新内部状态 「caseId」、「planId」、「approvalRecordId」、「actionType」；处理的关键状态/协议值包括 「{}」、「[]」，最终返回「ActionRecordEntity」。
    // 上游调用：「ActionRecordEntity.running(String,String,String,String,String,RiskLevel,String,String,String,String)」的上游调用点包括 「ActionRecordEntity.runningGoverned」、「CaseClosureServiceIntegrationTest.seed」。
    // 下游影响：「ActionRecordEntity.running(String,String,String,String,String,RiskLevel,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActionRecordEntity」交给调用方。
    // 系统意义：「ActionRecordEntity.running(String,String,String,String,String,RiskLevel,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ActionRecordEntity running(
            String id,
            String caseId,
            String planId,
            String approvalRecordId,
            String actionType,
            RiskLevel riskLevel,
            String idempotencyKey,
            String approvedBy,
            String executedBy,
            String requestJson) {
        ActionRecordEntity record = new ActionRecordEntity(id);
        record.caseId = caseId;
        record.planId = planId;
        record.approvalRecordId = approvalRecordId;
        record.actionType = actionType;
        record.riskLevel = riskLevel;
        record.idempotencyKey = idempotencyKey;
        record.approvedBy = approvedBy;
        record.executedBy = executedBy;
        record.requestJson = requestJson;
        record.resultJson = "{}";
        record.executionStatus = ExecutionStatus.RUNNING;
        record.attemptCount = 1;
        record.evidenceRefsJson = "[]";
        record.ruleRefsJson = "[]";
        record.agentRunRefsJson = "[]";
        record.createdBy = executedBy;
        return record;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.runningGoverned(String,String,String,String,String,RiskLevel,String,String,String,String,String,String,String,String,String)」。
    // 具体功能：「ActionRecordEntity.runningGoverned(String,String,String,String,String,RiskLevel,String,String,String,String,String,String,String,String,String)」：运行执行中Governed：先更新内部状态 「reviewPacketId」、「actionSnapshotHash」、「evidenceRefsJson」、「ruleRefsJson」；实际协作者为 「running」，最终返回「ActionRecordEntity」。
    // 上游调用：「ActionRecordEntity.runningGoverned(String,String,String,String,String,RiskLevel,String,String,String,String,String,String,String,String,String)」的上游调用点包括 「ToolExecutorService.prepare」。
    // 下游影响：「ActionRecordEntity.runningGoverned(String,String,String,String,String,RiskLevel,String,String,String,String,String,String,String,String,String)」向下依次触达 「running」；计算结果以「ActionRecordEntity」交给调用方。
    // 系统意义：「ActionRecordEntity.runningGoverned(String,String,String,String,String,RiskLevel,String,String,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static ActionRecordEntity runningGoverned(
            String id,
            String caseId,
            String planId,
            String approvalRecordId,
            String actionType,
            RiskLevel riskLevel,
            String idempotencyKey,
            String approvedBy,
            String executedBy,
            String requestJson,
            String reviewPacketId,
            String actionSnapshotHash,
            String evidenceRefsJson,
            String ruleRefsJson,
            String agentRunRefsJson) {
        ActionRecordEntity record =
                running(
                        id,
                        caseId,
                        planId,
                        approvalRecordId,
                        actionType,
                        riskLevel,
                        idempotencyKey,
                        approvedBy,
                        executedBy,
                        requestJson);
        record.reviewPacketId = reviewPacketId;
        record.actionSnapshotHash = actionSnapshotHash;
        record.evidenceRefsJson = evidenceRefsJson;
        record.ruleRefsJson = ruleRefsJson;
        record.agentRunRefsJson = agentRunRefsJson;
        return record;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.retry(String,String)」。
    // 具体功能：「ActionRecordEntity.retry(String,String)」：重试动作记录：先更新内部状态 「executionStatus」、「executedBy」、「requestJson」、「resultJson」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「{}」，最终返回「void」。
    // 上游调用：「ActionRecordEntity.retry(String,String)」由使用「ActionRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ActionRecordEntity.retry(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ActionRecordEntity.retry(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void retry(String executedBy, String requestJson) {
        if (executionStatus == ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("succeeded action cannot be retried");
        }
        this.executedBy = executedBy;
        this.requestJson = requestJson;
        this.resultJson = "{}";
        this.executionStatus = ExecutionStatus.RUNNING;
        this.errorCode = null;
        this.errorMessage = null;
        this.executionTime = null;
        this.attemptCount += 1;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.succeed(String)」。
    // 具体功能：「ActionRecordEntity.succeed(String)」：提供「succeed」的便捷重载：接收 「resultJson」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「ActionRecordEntity.succeed(String)」的上游调用点包括 「ActionRecordEntity.succeed」。
    // 下游影响：「ActionRecordEntity.succeed(String)」向下依次触达 「succeed」。
    // 系统意义：「ActionRecordEntity.succeed(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void succeed(String resultJson) {
        succeed(resultJson, null);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.succeed(String,String)」。
    // 具体功能：「ActionRecordEntity.succeed(String,String)」：标记成功动作记录：先更新内部状态 「resultJson」、「externalResultRef」、「executionStatus」、「errorCode」，最终返回「void」。
    // 上游调用：「ActionRecordEntity.succeed(String,String)」的上游调用点包括 「ActionRecordEntity.succeed」。
    // 下游影响：「ActionRecordEntity.succeed(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ActionRecordEntity.succeed(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void succeed(String resultJson, String externalResultRef) {
        this.resultJson = resultJson;
        this.externalResultRef = externalResultRef;
        this.executionStatus = ExecutionStatus.SUCCEEDED;
        this.errorCode = null;
        this.errorMessage = null;
        this.executionTime = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.fail(String,String,String)」。
    // 具体功能：「ActionRecordEntity.fail(String,String,String)」：标记失败动作记录：先更新内部状态 「resultJson」、「executionStatus」、「errorCode」、「errorMessage」，最终返回「void」。
    // 上游调用：「ActionRecordEntity.fail(String,String,String)」由使用「ActionRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ActionRecordEntity.fail(String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ActionRecordEntity.fail(String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void fail(String errorCode, String errorMessage, String resultJson) {
        this.resultJson = resultJson;
        this.executionStatus = ExecutionStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.executionTime = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.prePersist()」。
    // 具体功能：「ActionRecordEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「createdAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「ActionRecordEntity.prePersist()」由使用「ActionRecordEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「ActionRecordEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ActionRecordEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getCaseId()」。
    // 具体功能：「ActionRecordEntity.getCaseId()」：读取「ActionRecordEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getCaseId()」的上游调用点包括 「ToolExecutorService.auditStarted」、「ToolExecutorService.assertRecordMatches」、「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getPlanId()」。
    // 具体功能：「ActionRecordEntity.getPlanId()」：读取「ActionRecordEntity」中的「planId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getPlanId()」的上游调用点包括 「ToolExecutorService.assertRecordMatches」、「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getPlanId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getPlanId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPlanId() {
        return planId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getApprovalRecordId()」。
    // 具体功能：「ActionRecordEntity.getApprovalRecordId()」：读取「ActionRecordEntity」中的「approvalRecordId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getApprovalRecordId()」的上游调用点包括 「ToolExecutorService.assertRecordMatches」、「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getApprovalRecordId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getApprovalRecordId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getApprovalRecordId() {
        return approvalRecordId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getActionType()」。
    // 具体功能：「ActionRecordEntity.getActionType()」：读取「ActionRecordEntity」中的「actionType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getActionType()」的上游调用点包括 「ToolExecutorService.auditStarted」、「ToolExecutorService.assertRecordMatches」、「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getActionType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getActionType()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getActionType() {
        return actionType;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getRiskLevel()」。
    // 具体功能：「ActionRecordEntity.getRiskLevel()」：读取「ActionRecordEntity」中的「riskLevel」状态，向 JPA、应用服务或序列化层返回「RiskLevel」。
    // 上游调用：「ActionRecordEntity.getRiskLevel()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getRiskLevel()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RiskLevel」交给调用方。
    // 系统意义：「ActionRecordEntity.getRiskLevel()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getIdempotencyKey()」。
    // 具体功能：「ActionRecordEntity.getIdempotencyKey()」：读取「ActionRecordEntity」中的「idempotencyKey」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getIdempotencyKey()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getIdempotencyKey()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getIdempotencyKey()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getApprovedBy()」。
    // 具体功能：「ActionRecordEntity.getApprovedBy()」：读取「ActionRecordEntity」中的「approvedBy」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getApprovedBy()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getApprovedBy()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getApprovedBy()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getApprovedBy() {
        return approvedBy;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getExecutedBy()」。
    // 具体功能：「ActionRecordEntity.getExecutedBy()」：读取「ActionRecordEntity」中的「executedBy」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getExecutedBy()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getExecutedBy()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getExecutedBy()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getExecutedBy() {
        return executedBy;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getRequestJson()」。
    // 具体功能：「ActionRecordEntity.getRequestJson()」：读取「ActionRecordEntity」中的「requestJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getRequestJson()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getRequestJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getRequestJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRequestJson() {
        return requestJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getResultJson()」。
    // 具体功能：「ActionRecordEntity.getResultJson()」：读取「ActionRecordEntity」中的「resultJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getResultJson()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getResultJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getResultJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getResultJson() {
        return resultJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getExecutionStatus()」。
    // 具体功能：「ActionRecordEntity.getExecutionStatus()」：读取「ActionRecordEntity」中的「executionStatus」状态，向 JPA、应用服务或序列化层返回「ExecutionStatus」。
    // 上游调用：「ActionRecordEntity.getExecutionStatus()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getExecutionStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ExecutionStatus」交给调用方。
    // 系统意义：「ActionRecordEntity.getExecutionStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public ExecutionStatus getExecutionStatus() {
        return executionStatus;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getErrorCode()」。
    // 具体功能：「ActionRecordEntity.getErrorCode()」：读取「ActionRecordEntity」中的「errorCode」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getErrorCode()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getErrorCode()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getErrorCode()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getErrorCode() {
        return errorCode;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getErrorMessage()」。
    // 具体功能：「ActionRecordEntity.getErrorMessage()」：读取「ActionRecordEntity」中的「errorMessage」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getErrorMessage()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getErrorMessage()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getErrorMessage()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getErrorMessage() {
        return errorMessage;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getAttemptCount()」。
    // 具体功能：「ActionRecordEntity.getAttemptCount()」：读取「ActionRecordEntity」中的「attemptCount」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「ActionRecordEntity.getAttemptCount()」的上游调用点包括 「ToolExecutorService.auditStarted」、「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getAttemptCount()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「ActionRecordEntity.getAttemptCount()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getAttemptCount() {
        return attemptCount;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getExecutionTime()」。
    // 具体功能：「ActionRecordEntity.getExecutionTime()」：读取「ActionRecordEntity」中的「executionTime」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「ActionRecordEntity.getExecutionTime()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getExecutionTime()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「ActionRecordEntity.getExecutionTime()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getExecutionTime() {
        return executionTime;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getCreatedAt()」。
    // 具体功能：「ActionRecordEntity.getCreatedAt()」：读取「ActionRecordEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「ActionRecordEntity.getCreatedAt()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「ActionRecordEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getReviewPacketId()」。
    // 具体功能：「ActionRecordEntity.getReviewPacketId()」：读取「ActionRecordEntity」中的「reviewPacketId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getReviewPacketId()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getReviewPacketId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getReviewPacketId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getReviewPacketId() {
        return reviewPacketId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getActionSnapshotHash()」。
    // 具体功能：「ActionRecordEntity.getActionSnapshotHash()」：读取「ActionRecordEntity」中的「actionSnapshotHash」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getActionSnapshotHash()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getActionSnapshotHash()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getActionSnapshotHash()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getActionSnapshotHash() {
        return actionSnapshotHash;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getEvidenceRefsJson()」。
    // 具体功能：「ActionRecordEntity.getEvidenceRefsJson()」：读取「ActionRecordEntity」中的「evidenceRefsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getEvidenceRefsJson()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getEvidenceRefsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getEvidenceRefsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getEvidenceRefsJson() {
        return evidenceRefsJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getRuleRefsJson()」。
    // 具体功能：「ActionRecordEntity.getRuleRefsJson()」：读取「ActionRecordEntity」中的「ruleRefsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getRuleRefsJson()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getRuleRefsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getRuleRefsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRuleRefsJson() {
        return ruleRefsJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getAgentRunRefsJson()」。
    // 具体功能：「ActionRecordEntity.getAgentRunRefsJson()」：读取「ActionRecordEntity」中的「agentRunRefsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getAgentRunRefsJson()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getAgentRunRefsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getAgentRunRefsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getAgentRunRefsJson() {
        return agentRunRefsJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「ActionRecordEntity.getExternalResultRef()」。
    // 具体功能：「ActionRecordEntity.getExternalResultRef()」：读取「ActionRecordEntity」中的「externalResultRef」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「ActionRecordEntity.getExternalResultRef()」的上游调用点包括 「ToolExecutorService.view」。
    // 下游影响：「ActionRecordEntity.getExternalResultRef()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「ActionRecordEntity.getExternalResultRef()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getExternalResultRef() {
        return externalResultRef;
    }
}
