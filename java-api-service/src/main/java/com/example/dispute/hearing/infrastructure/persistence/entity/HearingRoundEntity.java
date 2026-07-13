/*
 * 所属模块：共享小法庭。
 * 文件职责：映射庭审轮次数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「open」、「waitForCounterparty」、「complete」、「getCaseId」、「getRoundNo」、「getRoundStatus」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingStopReason;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【共享小法庭 / JPA 实体层】类型「HearingRoundEntity」。
// 类型职责：映射庭审轮次数据库记录并保存可审计状态；本类型显式提供 「HearingRoundEntity」、「HearingRoundEntity」、「open」、「waitForCounterparty」、「complete」、「getCaseId」。
// 协作关系：主要由 「ActiveCourtroomContextAssembler.sealedRound」、「HearingCourtOrchestrator.command」、「HearingFinalDraftService.assertFinalRoundSealed」、「HearingRoundService.activeOrNextRound」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "hearing_round")
public class HearingRoundEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "hearing_state_id", length = 64)
    private String hearingStateId;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "round_status", length = 32, nullable = false)
    private HearingRoundStatus roundStatus;
    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;
    @Column(name = "round_deadline_at", nullable = false)
    private Instant roundDeadlineAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 64)
    private HearingStopReason stopReason;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json", nullable = false, columnDefinition = "jsonb")
    private String summaryJson;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.HearingRoundEntity()」。
    // 具体功能：「HearingRoundEntity.HearingRoundEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「HearingRoundEntity.HearingRoundEntity()」的上游创建点包括 「HearingRoundEntity.open」。
    // 下游影响：「HearingRoundEntity.HearingRoundEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRoundEntity.HearingRoundEntity()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected HearingRoundEntity() {}

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.HearingRoundEntity(String)」。
    // 具体功能：「HearingRoundEntity.HearingRoundEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「HearingRoundEntity.HearingRoundEntity(String)」的上游创建点包括 「HearingRoundEntity.open」。
    // 下游影响：「HearingRoundEntity.HearingRoundEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRoundEntity.HearingRoundEntity(String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private HearingRoundEntity(String id) {
        super(id);
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.open(String,String,String,int,int,Instant,Instant,String)」。
    // 具体功能：「HearingRoundEntity.open(String,String,String,int,int,Instant,Instant,String)」：开放庭审轮次：先更新内部状态 「caseId」、「hearingStateId」、「roundNo」、「roundStatus」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「{}」，最终返回「HearingRoundEntity」。
    // 上游调用：「HearingRoundEntity.open(String,String,String,int,int,Instant,Instant,String)」的上游调用点包括 「HearingRoundService.ensureInitialRoundOpen」、「HearingRoundService.completeNext」、「HearingRoundService.expire」、「HearingRoundService.activeOrNextRound」。
    // 下游影响：「HearingRoundEntity.open(String,String,String,int,int,Instant,Instant,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HearingRoundEntity」交给调用方。
    // 系统意义：「HearingRoundEntity.open(String,String,String,int,int,Instant,Instant,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public static HearingRoundEntity open(
            String id,
            String caseId,
            String hearingStateId,
            int roundNo,
            int dossierVersion,
            Instant roundDeadlineAt,
            Instant now,
            String actorId) {
        if (roundNo < 1 || roundNo > 5) {
            throw new IllegalArgumentException("roundNo must be between 1 and 5");
        }
        HearingRoundEntity entity = new HearingRoundEntity(id);
        entity.caseId = caseId;
        entity.hearingStateId = hearingStateId;
        entity.roundNo = roundNo;
        entity.roundStatus = HearingRoundStatus.OPEN;
        entity.dossierVersion = dossierVersion;
        entity.openedAt = now;
        entity.roundDeadlineAt = roundDeadlineAt;
        entity.summaryJson = "{}";
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.createdBy = actorId;
        entity.updatedBy = actorId;
        return entity;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.waitForCounterparty(Instant,String)」。
    // 具体功能：「HearingRoundEntity.waitForCounterparty(Instant,String)」：更新wait面向Counterparty：先更新内部状态 「roundStatus」、「updatedAt」、「updatedBy」；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「HearingRoundEntity.waitForCounterparty(Instant,String)」的上游调用点包括 「HearingRoundService.advanceAfterPartySubmissionIfReady」。
    // 下游影响：「HearingRoundEntity.waitForCounterparty(Instant,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRoundEntity.waitForCounterparty(Instant,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void waitForCounterparty(Instant now, String actorId) {
        if (roundStatus != HearingRoundStatus.OPEN
                && roundStatus != HearingRoundStatus.WAITING) {
            throw new IllegalStateException("round is already closed");
        }
        this.roundStatus = HearingRoundStatus.WAITING;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.complete(String,HearingStopReason,Instant,String)」。
    // 具体功能：「HearingRoundEntity.complete(String,HearingStopReason,Instant,String)」：完成庭审轮次：先更新内部状态 「summaryJson」、「stopReason」、「roundStatus」、「closedAt」；不满足前置条件时抛出 「IllegalStateException」，最终返回「void」。
    // 上游调用：「HearingRoundEntity.complete(String,HearingStopReason,Instant,String)」的上游调用点包括 「HearingRoundService.completeRoundAfterTimeout」、「HearingRoundService.advanceAfterPartySubmissionIfReady」。
    // 下游影响：「HearingRoundEntity.complete(String,HearingStopReason,Instant,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRoundEntity.complete(String,HearingStopReason,Instant,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public void complete(
            String summaryJson,
            HearingStopReason stopReason,
            Instant now,
            String actorId) {
        if (roundStatus != HearingRoundStatus.OPEN
                && roundStatus != HearingRoundStatus.WAITING) {
            throw new IllegalStateException("round is already closed");
        }
        this.summaryJson = summaryJson;
        this.stopReason = stopReason;
        this.roundStatus =
                stopReason == HearingStopReason.MAX_ROUNDS
                                || stopReason == HearingStopReason.DEADLINE_EXPIRED
                        ? HearingRoundStatus.FORCED_CLOSED
                        : HearingRoundStatus.COMPLETED;
        this.closedAt = now;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.getCaseId()」。
    // 具体功能：「HearingRoundEntity.getCaseId()」：读取「HearingRoundEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingRoundEntity.getCaseId()」由使用「HearingRoundEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「HearingRoundEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundEntity.getCaseId()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getCaseId() { return caseId; }
    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.getRoundNo()」。
    // 具体功能：「HearingRoundEntity.getRoundNo()」：读取「HearingRoundEntity」中的「roundNo」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「HearingRoundEntity.getRoundNo()」的上游调用点包括 「ActiveCourtroomContextAssembler.sealedRound」、「HearingCourtOrchestrator.command」、「HearingRoundService.statusView」、「HearingRoundService.assertWritableRound」。
    // 下游影响：「HearingRoundEntity.getRoundNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「HearingRoundEntity.getRoundNo()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public int getRoundNo() { return roundNo; }
    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.getRoundStatus()」。
    // 具体功能：「HearingRoundEntity.getRoundStatus()」：读取「HearingRoundEntity」中的「roundStatus」状态，向 JPA、应用服务或序列化层返回「HearingRoundStatus」。
    // 上游调用：「HearingRoundEntity.getRoundStatus()」的上游调用点包括 「ActiveCourtroomContextAssembler.sealedRound」、「HearingCourtOrchestrator.command」、「HearingFinalDraftService.assertFinalRoundSealed」、「HearingRoundService.statusView」。
    // 下游影响：「HearingRoundEntity.getRoundStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HearingRoundStatus」交给调用方。
    // 系统意义：「HearingRoundEntity.getRoundStatus()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public HearingRoundStatus getRoundStatus() { return roundStatus; }
    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.getDossierVersion()」。
    // 具体功能：「HearingRoundEntity.getDossierVersion()」：读取「HearingRoundEntity」中的「dossierVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「HearingRoundEntity.getDossierVersion()」的上游调用点包括 「ActiveCourtroomContextAssembler.sealedRound」、「HearingCourtOrchestrator.command」、「HearingRoundService.openNextRound」、「HearingRoundService.view」。
    // 下游影响：「HearingRoundEntity.getDossierVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「HearingRoundEntity.getDossierVersion()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public int getDossierVersion() { return dossierVersion; }
    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.getStopReason()」。
    // 具体功能：「HearingRoundEntity.getStopReason()」：读取「HearingRoundEntity」中的「stopReason」状态，向 JPA、应用服务或序列化层返回「HearingStopReason」。
    // 上游调用：「HearingRoundEntity.getStopReason()」的上游调用点包括 「ActiveCourtroomContextAssembler.sealedRound」、「HearingCourtOrchestrator.command」、「HearingRoundService.view」。
    // 下游影响：「HearingRoundEntity.getStopReason()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HearingStopReason」交给调用方。
    // 系统意义：「HearingRoundEntity.getStopReason()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public HearingStopReason getStopReason() { return stopReason; }
    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.getSummaryJson()」。
    // 具体功能：「HearingRoundEntity.getSummaryJson()」：读取「HearingRoundEntity」中的「summaryJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingRoundEntity.getSummaryJson()」的上游调用点包括 「ActiveCourtroomContextAssembler.sealedRound」、「HearingCourtOrchestrator.command」、「HearingRoundService.view」。
    // 下游影响：「HearingRoundEntity.getSummaryJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundEntity.getSummaryJson()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getSummaryJson() { return summaryJson; }
    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.getOpenedAt()」。
    // 具体功能：「HearingRoundEntity.getOpenedAt()」：读取「HearingRoundEntity」中的「openedAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「HearingRoundEntity.getOpenedAt()」的上游调用点包括 「HearingRoundService.view」。
    // 下游影响：「HearingRoundEntity.getOpenedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「HearingRoundEntity.getOpenedAt()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public Instant getOpenedAt() { return openedAt; }
    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.getRoundDeadlineAt()」。
    // 具体功能：「HearingRoundEntity.getRoundDeadlineAt()」：读取「HearingRoundEntity」中的「roundDeadlineAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「HearingRoundEntity.getRoundDeadlineAt()」的上游调用点包括 「HearingRoundService.statusView」、「HearingRoundService.assertWritableRound」、「HearingRoundService.view」。
    // 下游影响：「HearingRoundEntity.getRoundDeadlineAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「HearingRoundEntity.getRoundDeadlineAt()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public Instant getRoundDeadlineAt() { return roundDeadlineAt; }
    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundEntity.getClosedAt()」。
    // 具体功能：「HearingRoundEntity.getClosedAt()」：读取「HearingRoundEntity」中的「closedAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「HearingRoundEntity.getClosedAt()」的上游调用点包括 「HearingFinalDraftService.assertFinalRoundSealed」、「RecoveryCursor.after」、「HearingRoundService.assertWritableRound」、「HearingRoundService.completeRoundAfterTimeout」。
    // 下游影响：「HearingRoundEntity.getClosedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「HearingRoundEntity.getClosedAt()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public Instant getClosedAt() { return closedAt; }
}
