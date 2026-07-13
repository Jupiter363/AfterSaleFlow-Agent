/*
 * 所属模块：房间协作与权限。
 * 文件职责：映射案件阶段时钟数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「running」、「prePersist」、「preUpdate」、「getClockType」、「getClockStatus」、「getDeadlineAt」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.PhaseClockStatus;
import com.example.dispute.room.domain.PhaseClockType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

// 所属模块：【房间协作与权限 / JPA 实体层】类型「CasePhaseClockEntity」。
// 类型职责：映射案件阶段时钟数据库记录并保存可审计状态；本类型显式提供 「CasePhaseClockEntity」、「CasePhaseClockEntity」、「running」、「prePersist」、「preUpdate」、「getClockType」。
// 协作关系：主要由 「EvidenceCompletionService.sealEvidenceAndOpenHearing」、「ExternalCaseImportTransactionService.materializeCurrentRoom」、「IntakeRoomService.confirm」、「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "case_phase_clock")
public class CasePhaseClockEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "room_id", length = 64, nullable = false)
    private String roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "clock_type", length = 32, nullable = false)
    private PhaseClockType clockType;

    @Enumerated(EnumType.STRING)
    @Column(name = "clock_status", length = 32, nullable = false)
    private PhaseClockStatus clockStatus;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "deadline_at", nullable = false)
    private OffsetDateTime deadlineAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "temporal_workflow_id", length = 128, nullable = false)
    private String temporalWorkflowId;

    @Column(name = "temporal_run_id", length = 128)
    private String temporalRunId;

    @Column(name = "completion_reason", length = 64)
    private String completionReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.CasePhaseClockEntity()」。
    // 具体功能：「CasePhaseClockEntity.CasePhaseClockEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CasePhaseClockEntity.CasePhaseClockEntity()」的上游创建点包括 「CasePhaseClockEntity.running」。
    // 下游影响：「CasePhaseClockEntity.CasePhaseClockEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CasePhaseClockEntity.CasePhaseClockEntity()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected CasePhaseClockEntity() {}

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.CasePhaseClockEntity(String)」。
    // 具体功能：「CasePhaseClockEntity.CasePhaseClockEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CasePhaseClockEntity.CasePhaseClockEntity(String)」的上游创建点包括 「CasePhaseClockEntity.running」。
    // 下游影响：「CasePhaseClockEntity.CasePhaseClockEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CasePhaseClockEntity.CasePhaseClockEntity(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private CasePhaseClockEntity(String id) {
        super(id);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.running(String,String,String,PhaseClockType,OffsetDateTime,OffsetDateTime,String,String)」。
    // 具体功能：「CasePhaseClockEntity.running(String,String,String,PhaseClockType,OffsetDateTime,OffsetDateTime,String,String)」：运行执行中：先更新内部状态 「caseId」、「roomId」、「clockType」、「clockStatus」；实际协作者为 「Objects.requireNonNull」、「deadlineAt.isAfter」、「required」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「id」、「caseId」、「roomId」、「workflowId」，最终返回「CasePhaseClockEntity」。
    // 上游调用：「CasePhaseClockEntity.running(String,String,String,PhaseClockType,OffsetDateTime,OffsetDateTime,String,String)」的上游调用点包括 「ExternalCaseImportTransactionService.materializeCurrentRoom」、「EvidenceCompletionService.sealEvidenceAndOpenHearing」、「IntakeRoomService.confirm」、「EvidenceCompletionServiceTest.setUp」。
    // 下游影响：「CasePhaseClockEntity.running(String,String,String,PhaseClockType,OffsetDateTime,OffsetDateTime,String,String)」向下依次触达 「Objects.requireNonNull」、「deadlineAt.isAfter」、「required」；计算结果以「CasePhaseClockEntity」交给调用方。
    // 系统意义：「CasePhaseClockEntity.running(String,String,String,PhaseClockType,OffsetDateTime,OffsetDateTime,String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CasePhaseClockEntity running(
            String id,
            String caseId,
            String roomId,
            PhaseClockType type,
            OffsetDateTime startedAt,
            OffsetDateTime deadlineAt,
            String workflowId,
            String actorId) {
        if (!deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("deadlineAt must be after startedAt");
        }
        CasePhaseClockEntity entity = new CasePhaseClockEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.roomId = required(roomId, "roomId");
        entity.clockType = Objects.requireNonNull(type, "type must not be null");
        entity.clockStatus = PhaseClockStatus.RUNNING;
        entity.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        entity.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        entity.temporalWorkflowId = required(workflowId, "workflowId");
        entity.createdBy = required(actorId, "actorId");
        entity.updatedBy = actorId;
        return entity;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.prePersist()」。
    // 具体功能：「CasePhaseClockEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「CasePhaseClockEntity.prePersist()」由使用「CasePhaseClockEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CasePhaseClockEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CasePhaseClockEntity.prePersist()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.preUpdate()」。
    // 具体功能：「CasePhaseClockEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「CasePhaseClockEntity.preUpdate()」由使用「CasePhaseClockEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CasePhaseClockEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CasePhaseClockEntity.preUpdate()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.getClockType()」。
    // 具体功能：「CasePhaseClockEntity.getClockType()」：读取「CasePhaseClockEntity」中的「clockType」状态，向 JPA、应用服务或序列化层返回「PhaseClockType」。
    // 上游调用：「CasePhaseClockEntity.getClockType()」由使用「CasePhaseClockEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CasePhaseClockEntity.getClockType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「PhaseClockType」交给调用方。
    // 系统意义：「CasePhaseClockEntity.getClockType()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public PhaseClockType getClockType() {
        return clockType;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.getClockStatus()」。
    // 具体功能：「CasePhaseClockEntity.getClockStatus()」：读取「CasePhaseClockEntity」中的「clockStatus」状态，向 JPA、应用服务或序列化层返回「PhaseClockStatus」。
    // 上游调用：「CasePhaseClockEntity.getClockStatus()」的上游调用点包括 「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」。
    // 下游影响：「CasePhaseClockEntity.getClockStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「PhaseClockStatus」交给调用方。
    // 系统意义：「CasePhaseClockEntity.getClockStatus()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public PhaseClockStatus getClockStatus() {
        return clockStatus;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.getDeadlineAt()」。
    // 具体功能：「CasePhaseClockEntity.getDeadlineAt()」：读取「CasePhaseClockEntity」中的「deadlineAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「CasePhaseClockEntity.getDeadlineAt()」由使用「CasePhaseClockEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CasePhaseClockEntity.getDeadlineAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「CasePhaseClockEntity.getDeadlineAt()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public OffsetDateTime getDeadlineAt() {
        return deadlineAt;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.getCompletionReason()」。
    // 具体功能：「CasePhaseClockEntity.getCompletionReason()」：读取「CasePhaseClockEntity」中的「completionReason」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CasePhaseClockEntity.getCompletionReason()」由使用「CasePhaseClockEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CasePhaseClockEntity.getCompletionReason()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CasePhaseClockEntity.getCompletionReason()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getCompletionReason() {
        return completionReason;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.completeEarly(OffsetDateTime,String)」。
    // 具体功能：「CasePhaseClockEntity.completeEarly(OffsetDateTime,String)」：完成Early：先更新内部状态 「clockStatus」、「completedAt」、「completionReason」、「updatedBy」；实际协作者为 「required」；处理的关键状态/协议值包括 「BOTH_PARTIES_COMPLETED」、「actorId」，最终返回「void」。
    // 上游调用：「CasePhaseClockEntity.completeEarly(OffsetDateTime,String)」由使用「CasePhaseClockEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CasePhaseClockEntity.completeEarly(OffsetDateTime,String)」向下依次触达 「required」。
    // 系统意义：「CasePhaseClockEntity.completeEarly(OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void completeEarly(OffsetDateTime now, String actorId) {
        if (clockStatus != PhaseClockStatus.RUNNING) return;
        clockStatus = PhaseClockStatus.COMPLETED_EARLY;
        completedAt = now;
        completionReason = "BOTH_PARTIES_COMPLETED";
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.expire(OffsetDateTime,String)」。
    // 具体功能：「CasePhaseClockEntity.expire(OffsetDateTime,String)」：标记过期案件阶段时钟：先更新内部状态 「clockStatus」、「completedAt」、「completionReason」、「updatedBy」；实际协作者为 「required」；处理的关键状态/协议值包括 「DEADLINE_EXPIRED」、「actorId」，最终返回「void」。
    // 上游调用：「CasePhaseClockEntity.expire(OffsetDateTime,String)」由使用「CasePhaseClockEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CasePhaseClockEntity.expire(OffsetDateTime,String)」向下依次触达 「required」。
    // 系统意义：「CasePhaseClockEntity.expire(OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void expire(OffsetDateTime now, String actorId) {
        if (clockStatus != PhaseClockStatus.RUNNING) return;
        clockStatus = PhaseClockStatus.EXPIRED;
        completedAt = now;
        completionReason = "DEADLINE_EXPIRED";
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.complete(OffsetDateTime,String,String)」。
    // 具体功能：「CasePhaseClockEntity.complete(OffsetDateTime,String,String)」：完成案件阶段时钟：先更新内部状态 「clockStatus」、「completedAt」、「completionReason」、「updatedBy」；实际协作者为 「required」；处理的关键状态/协议值包括 「reason」、「actorId」，最终返回「void」。
    // 上游调用：「CasePhaseClockEntity.complete(OffsetDateTime,String,String)」由使用「CasePhaseClockEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CasePhaseClockEntity.complete(OffsetDateTime,String,String)」向下依次触达 「required」。
    // 系统意义：「CasePhaseClockEntity.complete(OffsetDateTime,String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void complete(
            OffsetDateTime now, String reason, String actorId) {
        if (clockStatus != PhaseClockStatus.RUNNING) return;
        clockStatus = PhaseClockStatus.COMPLETED_EARLY;
        completedAt = now;
        completionReason = required(reason, "reason");
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CasePhaseClockEntity.required(String,String)」。
    // 具体功能：「CasePhaseClockEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「CasePhaseClockEntity.required(String,String)」的上游调用点包括 「CasePhaseClockEntity.running」、「CasePhaseClockEntity.completeEarly」、「CasePhaseClockEntity.expire」、「CasePhaseClockEntity.complete」。
    // 下游影响：「CasePhaseClockEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CasePhaseClockEntity.required(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
