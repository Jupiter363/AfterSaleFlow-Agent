/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射Agent运行数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「completed」、「streamingPending」、「prePersist」、「markRunning」、「markCompleted」、「markFailed」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「AgentRunEntity」。
// 类型职责：映射Agent运行数据库记录并保存可审计状态；本类型显式提供 「AgentRunEntity」、「AgentRunEntity」、「completed」、「streamingPending」、「prePersist」、「markRunning」。
// 协作关系：主要由 「AgentRunCoordinator.accepted」、「AgentRunCoordinator.start」、「AgentRunQueryService.view」、「AgentRunStreamEventService.visibleTo」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "agent_run")
public class AgentRunEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64)
    private String caseId;

    @Column(name = "workflow_id", length = 128)
    private String workflowId;

    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    @Column(name = "agent_role", length = 128, nullable = false)
    private String agentRole;

    @Column(name = "profile_version", length = 64, nullable = false)
    private String profileVersion;

    @Column(name = "prompt_version", length = 64, nullable = false)
    private String promptVersion;

    @Column(name = "skill_version", length = 64, nullable = false)
    private String skillVersion;

    @Column(name = "ruleset_version", length = 64, nullable = false)
    private String rulesetVersion;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "run_status", length = 32, nullable = false)
    private String runStatus;

    @Column(name = "stop_reason", length = 64)
    private String stopReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_refs_json", nullable = false, columnDefinition = "jsonb")
    private String inputRefsJson;

    @Column(name = "output_ref", length = 128)
    private String outputRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_json", nullable = false, columnDefinition = "jsonb")
    private String validationJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_flags_json", nullable = false, columnDefinition = "jsonb")
    private String riskFlagsJson;

    @Column(name = "token_usage")
    private Integer tokenUsage;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "cost_amount", precision = 18, scale = 6)
    private BigDecimal costAmount;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "trace_id", length = 128, nullable = false)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "stream_operation", length = 64)
    private String streamOperation;

    @Column(name = "room_id", length = 64)
    private String roomId;

    @Column(name = "stream_endpoint", length = 256)
    private String streamEndpoint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stream_request_json", nullable = false, columnDefinition = "jsonb")
    private String streamRequestJson = "{}";

    @Column(name = "stream_request_hash", length = 64)
    private String streamRequestHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stream_result_json", columnDefinition = "jsonb")
    private String streamResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stream_audience_json", nullable = false, columnDefinition = "jsonb")
    private String streamAudienceJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "stream_audience_actor_ids_json",
            nullable = false,
            columnDefinition = "jsonb")
    private String streamAudienceActorIdsJson = "[]";

    @Column(name = "stream_idempotency_key", length = 128)
    private String streamIdempotencyKey;

    @Column(name = "stream_request_id", length = 128)
    private String streamRequestId;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "error_retryable")
    private Boolean errorRetryable;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.AgentRunEntity()」。
    // 具体功能：「AgentRunEntity.AgentRunEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AgentRunEntity.AgentRunEntity()」的上游创建点包括 「AgentRunEntity.completed」、「AgentRunEntity.streamingPending」。
    // 下游影响：「AgentRunEntity.AgentRunEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunEntity.AgentRunEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected AgentRunEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.AgentRunEntity(String)」。
    // 具体功能：「AgentRunEntity.AgentRunEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AgentRunEntity.AgentRunEntity(String)」的上游创建点包括 「AgentRunEntity.completed」、「AgentRunEntity.streamingPending」。
    // 下游影响：「AgentRunEntity.AgentRunEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunEntity.AgentRunEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private AgentRunEntity(String id) {
        super(id);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.completed(String,String,String,String,String,String,String,String,String,String,String,String,String,String,Integer,Long,BigDecimal,OffsetDateTime,String,String)」。
    // 具体功能：「AgentRunEntity.completed(String,String,String,String,String,String,String,String,String,String,String,String,String,String,Integer,Long,BigDecimal,OffsetDateTime,String,String)」：完成完成：先更新内部状态 「caseId」、「workflowId」、「agentId」、「agentRole」；处理的关键状态/协议值包括 「COMPLETED」、「OUTPUT_VALIDATED」，最终返回「AgentRunEntity」。
    // 上游调用：「AgentRunEntity.completed(String,String,String,String,String,String,String,String,String,String,String,String,String,String,Integer,Long,BigDecimal,OffsetDateTime,String,String)」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」、「AgentRunEntityTest.completedRunRetainsGovernanceCostAndTraceMetadata」。
    // 下游影响：「AgentRunEntity.completed(String,String,String,String,String,String,String,String,String,String,String,String,String,String,Integer,Long,BigDecimal,OffsetDateTime,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「AgentRunEntity」交给调用方。
    // 系统意义：「AgentRunEntity.completed(String,String,String,String,String,String,String,String,String,String,String,String,String,String,Integer,Long,BigDecimal,OffsetDateTime,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static AgentRunEntity completed(
            String id,
            String caseId,
            String workflowId,
            String agentId,
            String agentRole,
            String profileVersion,
            String promptVersion,
            String skillVersion,
            String rulesetVersion,
            String model,
            String inputRefsJson,
            String outputRef,
            String validationJson,
            String riskFlagsJson,
            Integer tokenUsage,
            Long latencyMs,
            BigDecimal costAmount,
            OffsetDateTime startedAt,
            String traceId,
            String actorId) {
        AgentRunEntity run = new AgentRunEntity(id);
        run.caseId = caseId;
        run.workflowId = workflowId;
        run.agentId = agentId;
        run.agentRole = agentRole;
        run.profileVersion = profileVersion;
        run.promptVersion = promptVersion;
        run.skillVersion = skillVersion;
        run.rulesetVersion = rulesetVersion;
        run.model = model;
        run.runStatus = "COMPLETED";
        run.stopReason = "OUTPUT_VALIDATED";
        run.inputRefsJson = inputRefsJson;
        run.outputRef = outputRef;
        run.validationJson = validationJson;
        run.riskFlagsJson = riskFlagsJson;
        run.tokenUsage = tokenUsage;
        run.latencyMs = latencyMs;
        run.costAmount = costAmount;
        run.startedAt = startedAt;
        run.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        run.traceId = traceId;
        run.createdBy = actorId;
        return run;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.streamingPending(String,String,String,String,String,String,String,String,String,String,String,String,String,String)」。
    // 具体功能：「AgentRunEntity.streamingPending(String,String,String,String,String,String,String,String,String,String,String,String,String,String)」：消费流streaming待处理：先更新内部状态 「caseId」、「roomId」、「workflowId」、「agentId」；实际协作者为 「required」；处理的关键状态/协议值包括 「id」、「caseId」、「AGENT_STREAM_」、「agent-stream:」，最终返回「AgentRunEntity」。
    // 上游调用：「AgentRunEntity.streamingPending(String,String,String,String,String,String,String,String,String,String,String,String,String,String)」的上游调用点包括 「AgentRunCoordinator.start」、「AgentRunCoordinatorTest.pendingRun」、「AgentRunLifecycleServiceTest.pendingIntakeRun」、「AgentRunStreamEventServiceTest.run」。
    // 下游影响：「AgentRunEntity.streamingPending(String,String,String,String,String,String,String,String,String,String,String,String,String,String)」向下依次触达 「required」；计算结果以「AgentRunEntity」交给调用方。
    // 系统意义：「AgentRunEntity.streamingPending(String,String,String,String,String,String,String,String,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static AgentRunEntity streamingPending(
            String id,
            String caseId,
            String roomId,
            String operation,
            String endpoint,
            String agentRole,
            String requestJson,
            String requestHash,
            String audienceJson,
            String audienceActorIdsJson,
            String idempotencyKey,
            String traceId,
            String requestId,
            String actorId) {
        AgentRunEntity run = new AgentRunEntity(required(id, "id"));
        run.caseId = required(caseId, "caseId");
        run.roomId = roomId;
        run.workflowId = "AGENT_STREAM_" + caseId;
        run.agentId = "agent-stream:" + required(operation, "operation").toLowerCase();
        run.agentRole = required(agentRole, "agentRole");
        run.profileVersion = "runtime";
        run.promptVersion = "runtime";
        run.skillVersion = "runtime";
        run.rulesetVersion = "agent_stream.v1";
        run.runStatus = "PENDING";
        run.inputRefsJson = "[]";
        run.validationJson = "{}";
        run.riskFlagsJson = "[]";
        run.startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        run.traceId = required(traceId, "traceId");
        run.createdBy = required(actorId, "actorId");
        run.streamOperation = operation;
        run.streamEndpoint = required(endpoint, "endpoint");
        run.streamRequestJson = required(requestJson, "requestJson");
        run.streamRequestHash = required(requestHash, "requestHash");
        run.streamAudienceJson = required(audienceJson, "audienceJson");
        run.streamAudienceActorIdsJson =
                required(audienceActorIdsJson, "audienceActorIdsJson");
        run.streamIdempotencyKey = required(idempotencyKey, "idempotencyKey");
        run.streamRequestId = required(requestId, "requestId");
        run.updatedAt = run.startedAt;
        return run;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.prePersist()」。
    // 具体功能：「AgentRunEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「AgentRunEntity.prePersist()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.markRunning()」。
    // 具体功能：「AgentRunEntity.markRunning()」：标记执行中：先更新内部状态 「runStatus」、「startedAt」、「updatedAt」；实际协作者为 「requireStatus」；处理的关键状态/协议值包括 「PENDING」、「RUNNING」，最终返回「void」。
    // 上游调用：「AgentRunEntity.markRunning()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.markRunning()」向下依次触达 「requireStatus」。
    // 系统意义：「AgentRunEntity.markRunning()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void markRunning() {
        requireStatus("PENDING");
        runStatus = "RUNNING";
        startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = startedAt;
    }

    public void markProgress() {
        requireStatus("RUNNING");
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.markCompleted(String,Integer,Long,String)」。
    // 具体功能：「AgentRunEntity.markCompleted(String,Integer,Long,String)」：标记完成：先更新内部状态 「streamResultJson」、「tokenUsage」、「latencyMs」、「model」；实际协作者为 「requireStatus」、「required」；处理的关键状态/协议值包括 「RUNNING」、「resultJson」、「COMPLETED」、「OUTPUT_VALIDATED」，最终返回「void」。
    // 上游调用：「AgentRunEntity.markCompleted(String,Integer,Long,String)」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.markCompleted(String,Integer,Long,String)」向下依次触达 「requireStatus」、「required」。
    // 系统意义：「AgentRunEntity.markCompleted(String,Integer,Long,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void markCompleted(
            String resultJson,
            Integer totalTokenUsage,
            Long measuredLatencyMs,
            String resolvedModel) {
        requireStatus("RUNNING");
        streamResultJson = required(resultJson, "resultJson");
        tokenUsage = totalTokenUsage;
        latencyMs = measuredLatencyMs;
        if (resolvedModel != null && !resolvedModel.isBlank()) {
            model = resolvedModel;
        }
        runStatus = "COMPLETED";
        stopReason = "OUTPUT_VALIDATED";
        completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = completedAt;
        errorCode = null;
        errorMessage = null;
        errorRetryable = null;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.markFailed(String,String,boolean,Long)」。
    // 具体功能：「AgentRunEntity.markFailed(String,String,boolean,Long)」：标记失败：先更新内部状态 「errorCode」、「errorMessage」、「errorRetryable」、「latencyMs」；实际协作者为 「required」；处理的关键状态/协议值包括 「COMPLETED」、「FAILED」、「failureCode」、「failureMessage」，最终返回「void」。
    // 上游调用：「AgentRunEntity.markFailed(String,String,boolean,Long)」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.markFailed(String,String,boolean,Long)」向下依次触达 「required」。
    // 系统意义：「AgentRunEntity.markFailed(String,String,boolean,Long)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void markFailed(
            String failureCode,
            String failureMessage,
            boolean retryable,
            Long measuredLatencyMs) {
        if ("COMPLETED".equals(runStatus) || "FAILED".equals(runStatus)) {
            return;
        }
        errorCode = required(failureCode, "failureCode");
        errorMessage = required(failureMessage, "failureMessage");
        errorRetryable = retryable;
        latencyMs = measuredLatencyMs;
        runStatus = "FAILED";
        stopReason = "STREAM_FAILED";
        completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = completedAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.requireStatus(String)」。
    // 具体功能：「AgentRunEntity.requireStatus(String)」：强制校验状态；实际协作者为 「getId」；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「AgentRunEntity.requireStatus(String)」的上游调用点包括 「AgentRunEntity.markRunning」、「AgentRunEntity.markCompleted」。
    // 下游影响：「AgentRunEntity.requireStatus(String)」向下依次触达 「getId」。
    // 系统意义：「AgentRunEntity.requireStatus(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    private void requireStatus(String expected) {
        if (!expected.equals(runStatus)) {
            throw new IllegalStateException(
                    "agent run " + getId() + " must be " + expected + " but was " + runStatus);
        }
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.attachOutput(String)」。
    // 具体功能：「AgentRunEntity.attachOutput(String)」：按attach输出：先更新内部状态 「outputRef」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「RUNNING」、「COMPLETED」，最终返回「void」。
    // 上游调用：「AgentRunEntity.attachOutput(String)」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.attachOutput(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunEntity.attachOutput(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void attachOutput(String outputRef) {
        if (!"RUNNING".equals(runStatus) && !"COMPLETED".equals(runStatus)) {
            throw new IllegalStateException(
                    "only running or completed Agent Runs can reference output");
        }
        this.outputRef = outputRef;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getCaseId()」。
    // 具体功能：「AgentRunEntity.getCaseId()」：读取「AgentRunEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getCaseId()」的上游调用点包括 「AgentRunQueryService.view」。
    // 下游影响：「AgentRunEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getWorkflowId()」。
    // 具体功能：「AgentRunEntity.getWorkflowId()」：读取「AgentRunEntity」中的「workflowId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getWorkflowId()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getWorkflowId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getWorkflowId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getWorkflowId() {
        return workflowId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getRunStatus()」。
    // 具体功能：「AgentRunEntity.getRunStatus()」：读取「AgentRunEntity」中的「runStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getRunStatus()」的上游调用点包括 「AgentRunCoordinator.accepted」、「AgentRunQueryService.view」。
    // 下游影响：「AgentRunEntity.getRunStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getRunStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRunStatus() {
        return runStatus;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getInputRefsJson()」。
    // 具体功能：「AgentRunEntity.getInputRefsJson()」：读取「AgentRunEntity」中的「inputRefsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getInputRefsJson()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getInputRefsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getInputRefsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getInputRefsJson() {
        return inputRefsJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getOutputRef()」。
    // 具体功能：「AgentRunEntity.getOutputRef()」：读取「AgentRunEntity」中的「outputRef」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getOutputRef()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getOutputRef()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getOutputRef()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getOutputRef() {
        return outputRef;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getTokenUsage()」。
    // 具体功能：「AgentRunEntity.getTokenUsage()」：读取「AgentRunEntity」中的「tokenUsage」状态，向 JPA、应用服务或序列化层返回「Integer」。
    // 上游调用：「AgentRunEntity.getTokenUsage()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getTokenUsage()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Integer」交给调用方。
    // 系统意义：「AgentRunEntity.getTokenUsage()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public Integer getTokenUsage() {
        return tokenUsage;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getLatencyMs()」。
    // 具体功能：「AgentRunEntity.getLatencyMs()」：读取「AgentRunEntity」中的「latencyMs」状态，向 JPA、应用服务或序列化层返回「Long」。
    // 上游调用：「AgentRunEntity.getLatencyMs()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getLatencyMs()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Long」交给调用方。
    // 系统意义：「AgentRunEntity.getLatencyMs()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public Long getLatencyMs() {
        return latencyMs;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getCostAmount()」。
    // 具体功能：「AgentRunEntity.getCostAmount()」：读取「AgentRunEntity」中的「costAmount」状态，向 JPA、应用服务或序列化层返回「BigDecimal」。
    // 上游调用：「AgentRunEntity.getCostAmount()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getCostAmount()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「BigDecimal」交给调用方。
    // 系统意义：「AgentRunEntity.getCostAmount()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public BigDecimal getCostAmount() {
        return costAmount;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getTraceId()」。
    // 具体功能：「AgentRunEntity.getTraceId()」：读取「AgentRunEntity」中的「traceId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getTraceId()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getTraceId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getTraceId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getTraceId() {
        return traceId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getRoomId()」。
    // 具体功能：「AgentRunEntity.getRoomId()」：读取「AgentRunEntity」中的「roomId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getRoomId()」的上游调用点包括 「AgentRunQueryService.view」。
    // 下游影响：「AgentRunEntity.getRoomId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getRoomId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getRoomId() {
        return roomId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStreamOperation()」。
    // 具体功能：「AgentRunEntity.getStreamOperation()」：读取「AgentRunEntity」中的「streamOperation」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getStreamOperation()」的上游调用点包括 「AgentRunQueryService.view」。
    // 下游影响：「AgentRunEntity.getStreamOperation()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getStreamOperation()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getStreamOperation() {
        return streamOperation;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStreamEndpoint()」。
    // 具体功能：「AgentRunEntity.getStreamEndpoint()」：读取「AgentRunEntity」中的「streamEndpoint」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getStreamEndpoint()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getStreamEndpoint()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getStreamEndpoint()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getStreamEndpoint() {
        return streamEndpoint;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStreamRequestJson()」。
    // 具体功能：「AgentRunEntity.getStreamRequestJson()」：读取「AgentRunEntity」中的「streamRequestJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getStreamRequestJson()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getStreamRequestJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getStreamRequestJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getStreamRequestJson() {
        return streamRequestJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStreamRequestHash()」。
    // 具体功能：「AgentRunEntity.getStreamRequestHash()」：读取「AgentRunEntity」中的「streamRequestHash」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getStreamRequestHash()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getStreamRequestHash()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getStreamRequestHash()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getStreamRequestHash() {
        return streamRequestHash;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStreamResultJson()」。
    // 具体功能：「AgentRunEntity.getStreamResultJson()」：读取「AgentRunEntity」中的「streamResultJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getStreamResultJson()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getStreamResultJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getStreamResultJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getStreamResultJson() {
        return streamResultJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStreamAudienceJson()」。
    // 具体功能：「AgentRunEntity.getStreamAudienceJson()」：读取「AgentRunEntity」中的「streamAudienceJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getStreamAudienceJson()」的上游调用点包括 「AgentRunStreamEventService.visibleTo」。
    // 下游影响：「AgentRunEntity.getStreamAudienceJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getStreamAudienceJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getStreamAudienceJson() {
        return streamAudienceJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStreamAudienceActorIdsJson()」。
    // 具体功能：「AgentRunEntity.getStreamAudienceActorIdsJson()」：读取「AgentRunEntity」中的「streamAudienceActorIdsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getStreamAudienceActorIdsJson()」的上游调用点包括 「AgentRunStreamEventService.visibleTo」。
    // 下游影响：「AgentRunEntity.getStreamAudienceActorIdsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getStreamAudienceActorIdsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getStreamAudienceActorIdsJson() {
        return streamAudienceActorIdsJson;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStreamIdempotencyKey()」。
    // 具体功能：「AgentRunEntity.getStreamIdempotencyKey()」：读取「AgentRunEntity」中的「streamIdempotencyKey」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getStreamIdempotencyKey()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getStreamIdempotencyKey()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getStreamIdempotencyKey()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getStreamIdempotencyKey() {
        return streamIdempotencyKey;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStreamRequestId()」。
    // 具体功能：「AgentRunEntity.getStreamRequestId()」：读取「AgentRunEntity」中的「streamRequestId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getStreamRequestId()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.getStreamRequestId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getStreamRequestId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getStreamRequestId() {
        return streamRequestId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getErrorCode()」。
    // 具体功能：「AgentRunEntity.getErrorCode()」：读取「AgentRunEntity」中的「errorCode」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunEntity.getErrorCode()」的上游调用点包括 「AgentRunQueryService.view」。
    // 下游影响：「AgentRunEntity.getErrorCode()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.getErrorCode()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getErrorCode() {
        return errorCode;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getErrorRetryable()」。
    // 具体功能：「AgentRunEntity.getErrorRetryable()」：读取「AgentRunEntity」中的「errorRetryable」状态，向 JPA、应用服务或序列化层返回「Boolean」。
    // 上游调用：「AgentRunEntity.getErrorRetryable()」的上游调用点包括 「AgentRunQueryService.view」。
    // 下游影响：「AgentRunEntity.getErrorRetryable()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Boolean」交给调用方。
    // 系统意义：「AgentRunEntity.getErrorRetryable()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public Boolean getErrorRetryable() {
        return errorRetryable;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getStartedAt()」。
    // 具体功能：「AgentRunEntity.getStartedAt()」：读取「AgentRunEntity」中的「startedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「AgentRunEntity.getStartedAt()」的上游调用点包括 「AgentRunCoordinator.accepted」、「AgentRunQueryService.view」。
    // 下游影响：「AgentRunEntity.getStartedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「AgentRunEntity.getStartedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getCompletedAt()」。
    // 具体功能：「AgentRunEntity.getCompletedAt()」：读取「AgentRunEntity」中的「completedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「AgentRunEntity.getCompletedAt()」的上游调用点包括 「AgentRunQueryService.view」。
    // 下游影响：「AgentRunEntity.getCompletedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「AgentRunEntity.getCompletedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getCreatedAt()」。
    // 具体功能：「AgentRunEntity.getCreatedAt()」：读取「AgentRunEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「AgentRunEntity.getCreatedAt()」的上游调用点包括 「AgentRunCoordinator.accepted」。
    // 下游影响：「AgentRunEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「AgentRunEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.getUpdatedAt()」。
    // 具体功能：「AgentRunEntity.getUpdatedAt()」：读取「AgentRunEntity」中的「updatedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「AgentRunEntity.getUpdatedAt()」的上游调用点包括 「AgentRunQueryService.view」。
    // 下游影响：「AgentRunEntity.getUpdatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「AgentRunEntity.getUpdatedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.isTerminal()」。
    // 具体功能：「AgentRunEntity.isTerminal()」：判断是否Terminal；处理的关键状态/协议值包括 「COMPLETED」、「FAILED」，最终返回「boolean」。
    // 上游调用：「AgentRunEntity.isTerminal()」由使用「AgentRunEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「AgentRunEntity.isTerminal()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「AgentRunEntity.isTerminal()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public boolean isTerminal() {
        return "COMPLETED".equals(runStatus) || "FAILED".equals(runStatus);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「AgentRunEntity.required(String,String)」。
    // 具体功能：「AgentRunEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「AgentRunEntity.required(String,String)」的上游调用点包括 「AgentRunEntity.streamingPending」、「AgentRunEntity.markCompleted」、「AgentRunEntity.markFailed」。
    // 下游影响：「AgentRunEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunEntity.required(String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
