/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：映射庭审状态数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「start」、「applyAnalysis」、「markRunning」、「complete」、「prePersist」、「preUpdate」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.entity;

import com.example.dispute.domain.model.HearingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【PostgreSQL 事实模型 / JPA 实体层】类型「HearingStateEntity」。
// 类型职责：映射庭审状态数据库记录并保存可审计状态；本类型显式提供 「HearingStateEntity」、「HearingStateEntity」、「start」、「applyAnalysis」、「markRunning」、「complete」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.initializeHearing」、「HearingCourtBootstrapService.courtroomContext」、「HearingCourtBootstrapService.ensureHearingState」、「HearingCourtBootstrapService.recordSnapshotIfAbsent」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "hearing_state")
public class HearingStateEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false, unique = true)
    private String caseId;

    @Column(name = "workflow_id", length = 128, nullable = false, unique = true)
    private String workflowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "hearing_status", length = 64, nullable = false)
    private HearingStatus hearingStatus;

    @Column(name = "current_node", length = 64)
    private String currentNode;

    @Column(name = "round_no", nullable = false)
    private int roundNo;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "manual_required", nullable = false)
    private boolean manualRequired;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_state_json", nullable = false, columnDefinition = "jsonb")
    private String graphStateJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_requests_json", nullable = false, columnDefinition = "jsonb")
    private String pendingRequestsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manual_flags_json", nullable = false, columnDefinition = "jsonb")
    private String manualFlagsJson;

    @Column(name = "waiting_until")
    private OffsetDateTime waitingUntil;

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

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.HearingStateEntity()」。
    // 具体功能：「HearingStateEntity.HearingStateEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「HearingStateEntity.HearingStateEntity()」的上游创建点包括 「HearingStateEntity.start」。
    // 下游影响：「HearingStateEntity.HearingStateEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingStateEntity.HearingStateEntity()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected HearingStateEntity() {}

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.HearingStateEntity(String)」。
    // 具体功能：「HearingStateEntity.HearingStateEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「HearingStateEntity.HearingStateEntity(String)」的上游创建点包括 「HearingStateEntity.start」。
    // 下游影响：「HearingStateEntity.HearingStateEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingStateEntity.HearingStateEntity(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private HearingStateEntity(String id) {
        super(id);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.start(String,String,String,String)」。
    // 具体功能：「HearingStateEntity.start(String,String,String,String)」：启动庭审状态：先更新内部状态 「caseId」、「workflowId」、「hearingStatus」、「currentNode」；处理的关键状态/协议值包括 「C0_HEARING_CONTROLLER」、「{}」、「[]」，最终返回「HearingStateEntity」。
    // 上游调用：「HearingStateEntity.start(String,String,String,String)」的上游调用点包括 「HearingCourtBootstrapService.ensureHearingState」、「CaseFulfillmentDisputeActivitiesImpl.initializeHearing」、「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」、「HearingCollaborationIntegrationTest.completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting」。
    // 下游影响：「HearingStateEntity.start(String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HearingStateEntity」交给调用方。
    // 系统意义：「HearingStateEntity.start(String,String,String,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public static HearingStateEntity start(
            String id, String caseId, String workflowId, String actorId) {
        HearingStateEntity state = new HearingStateEntity(id);
        state.caseId = caseId;
        state.workflowId = workflowId;
        state.hearingStatus = HearingStatus.RUNNING;
        state.currentNode = "C0_HEARING_CONTROLLER";
        state.graphStateJson = "{}";
        state.pendingRequestsJson = "[]";
        state.manualFlagsJson = "[]";
        state.createdBy = actorId;
        state.updatedBy = actorId;
        return state;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.applyAnalysis(int,String,BigDecimal,boolean,boolean,String,String,String,OffsetDateTime,String)」。
    // 具体功能：「HearingStateEntity.applyAnalysis(int,String,BigDecimal,boolean,boolean,String,String,String,OffsetDateTime,String)」：应用Analysis：先更新内部状态 「roundNo」、「currentNode」、「confidence」、「graphStateJson」，最终返回「void」。
    // 上游调用：「HearingStateEntity.applyAnalysis(int,String,BigDecimal,boolean,boolean,String,String,String,OffsetDateTime,String)」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.applyAnalysis(int,String,BigDecimal,boolean,boolean,String,String,String,OffsetDateTime,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingStateEntity.applyAnalysis(int,String,BigDecimal,boolean,boolean,String,String,String,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void applyAnalysis(
            int roundNo,
            String currentNode,
            BigDecimal confidence,
            boolean requiresEvidence,
            boolean manualRequired,
            String graphStateJson,
            String pendingRequestsJson,
            String manualFlagsJson,
            OffsetDateTime waitingUntil,
            String actorId) {
        this.roundNo = roundNo;
        this.currentNode = currentNode;
        this.confidence = confidence;
        this.manualRequired |= manualRequired;
        this.graphStateJson = graphStateJson;
        this.pendingRequestsJson = pendingRequestsJson;
        this.manualFlagsJson = manualFlagsJson;
        this.waitingUntil = requiresEvidence ? waitingUntil : null;
        this.hearingStatus =
                requiresEvidence
                        ? HearingStatus.WAITING_EVIDENCE
                        : HearingStatus.RUNNING;
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.markRunning(String)」。
    // 具体功能：「HearingStateEntity.markRunning(String)」：标记执行中：先更新内部状态 「hearingStatus」、「waitingUntil」、「updatedBy」，最终返回「void」。
    // 上游调用：「HearingStateEntity.markRunning(String)」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.markRunning(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingStateEntity.markRunning(String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void markRunning(String actorId) {
        hearingStatus = HearingStatus.RUNNING;
        waitingUntil = null;
        updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.complete(boolean,String)」。
    // 具体功能：「HearingStateEntity.complete(boolean,String)」：完成庭审状态：先更新内部状态 「hearingStatus」、「currentNode」、「waitingUntil」、「completedAt」；处理的关键状态/协议值包括 「C6_ADJUDICATION_DRAFT」，最终返回「void」。
    // 上游调用：「HearingStateEntity.complete(boolean,String)」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.complete(boolean,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingStateEntity.complete(boolean,String)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public void complete(boolean manualRequired, String actorId) {
        this.manualRequired |= manualRequired;
        this.hearingStatus = HearingStatus.COMPLETED;
        this.currentNode = "C6_ADJUDICATION_DRAFT";
        this.waitingUntil = null;
        this.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedBy = actorId;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.prePersist()」。
    // 具体功能：「HearingStateEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「HearingStateEntity.prePersist()」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingStateEntity.prePersist()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.preUpdate()」。
    // 具体功能：「HearingStateEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「HearingStateEntity.preUpdate()」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingStateEntity.preUpdate()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.getCaseId()」。
    // 具体功能：「HearingStateEntity.getCaseId()」：读取「HearingStateEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingStateEntity.getCaseId()」的上游调用点包括 「HearingOutcomeOrchestrationService.orchestrate」。
    // 下游影响：「HearingStateEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingStateEntity.getCaseId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCaseId() { return caseId; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.getWorkflowId()」。
    // 具体功能：「HearingStateEntity.getWorkflowId()」：读取「HearingStateEntity」中的「workflowId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingStateEntity.getWorkflowId()」的上游调用点包括 「HearingCourtBootstrapService.courtroomContext」、「HearingCourtBootstrapService.recordSnapshotIfAbsent」、「HearingOutcomeOrchestrationService.orchestrate」。
    // 下游影响：「HearingStateEntity.getWorkflowId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingStateEntity.getWorkflowId()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getWorkflowId() { return workflowId; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.getHearingStatus()」。
    // 具体功能：「HearingStateEntity.getHearingStatus()」：读取「HearingStateEntity」中的「hearingStatus」状态，向 JPA、应用服务或序列化层返回「HearingStatus」。
    // 上游调用：「HearingStateEntity.getHearingStatus()」的上游调用点包括 「HearingOutcomeOrchestrationService.orchestrate」。
    // 下游影响：「HearingStateEntity.getHearingStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HearingStatus」交给调用方。
    // 系统意义：「HearingStateEntity.getHearingStatus()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public HearingStatus getHearingStatus() { return hearingStatus; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.getCurrentNode()」。
    // 具体功能：「HearingStateEntity.getCurrentNode()」：读取「HearingStateEntity」中的「currentNode」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingStateEntity.getCurrentNode()」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.getCurrentNode()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingStateEntity.getCurrentNode()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getCurrentNode() { return currentNode; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.getRoundNo()」。
    // 具体功能：「HearingStateEntity.getRoundNo()」：读取「HearingStateEntity」中的「roundNo」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「HearingStateEntity.getRoundNo()」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.getRoundNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「HearingStateEntity.getRoundNo()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public int getRoundNo() { return roundNo; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.getConfidence()」。
    // 具体功能：「HearingStateEntity.getConfidence()」：读取「HearingStateEntity」中的「confidence」状态，向 JPA、应用服务或序列化层返回「BigDecimal」。
    // 上游调用：「HearingStateEntity.getConfidence()」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.getConfidence()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「BigDecimal」交给调用方。
    // 系统意义：「HearingStateEntity.getConfidence()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public BigDecimal getConfidence() { return confidence; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.isManualRequired()」。
    // 具体功能：「HearingStateEntity.isManualRequired()」：判断是否人工接管是否需要，最终返回「boolean」。
    // 上游调用：「HearingStateEntity.isManualRequired()」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.isManualRequired()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingStateEntity.isManualRequired()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public boolean isManualRequired() { return manualRequired; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.getPendingRequestsJson()」。
    // 具体功能：「HearingStateEntity.getPendingRequestsJson()」：读取「HearingStateEntity」中的「pendingRequestsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingStateEntity.getPendingRequestsJson()」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.getPendingRequestsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingStateEntity.getPendingRequestsJson()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public String getPendingRequestsJson() { return pendingRequestsJson; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.getWaitingUntil()」。
    // 具体功能：「HearingStateEntity.getWaitingUntil()」：读取「HearingStateEntity」中的「waitingUntil」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「HearingStateEntity.getWaitingUntil()」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.getWaitingUntil()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「HearingStateEntity.getWaitingUntil()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getWaitingUntil() { return waitingUntil; }
    // 所属模块：【PostgreSQL 事实模型 / JPA 实体层】「HearingStateEntity.getCompletedAt()」。
    // 具体功能：「HearingStateEntity.getCompletedAt()」：读取「HearingStateEntity」中的「completedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「HearingStateEntity.getCompletedAt()」由使用「HearingStateEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingStateEntity.getCompletedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「HearingStateEntity.getCompletedAt()」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
