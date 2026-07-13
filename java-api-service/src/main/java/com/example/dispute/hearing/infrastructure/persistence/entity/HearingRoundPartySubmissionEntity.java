/*
 * 所属模块：共享小法庭。
 * 文件职责：映射庭审轮次当事方提交数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「submit」、「getParticipantRole」、「getSubmissionSource」、「getSubmissionJson」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【共享小法庭 / JPA 实体层】类型「HearingRoundPartySubmissionEntity」。
// 类型职责：映射庭审轮次当事方提交数据库记录并保存可审计状态；本类型显式提供 「HearingRoundPartySubmissionEntity」、「HearingRoundPartySubmissionEntity」、「submit」、「getParticipantRole」、「getSubmissionSource」、「getSubmissionJson」。
// 协作关系：主要由 「ActiveCourtroomContextAssembler.submissionNode」、「HearingRoundService.autoTimeoutSubmission」、「HearingRoundService.recordPartyMessageSubmission」、「HearingRoundService.submitParty」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "hearing_round_party_submission")
public class HearingRoundPartySubmissionEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "round_id", length = 64, nullable = false)
    private String roundId;
    @Column(name = "round_no", nullable = false)
    private int roundNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32, nullable = false)
    private ActorRole participantRole;
    @Column(name = "participant_id", length = 128, nullable = false)
    private String participantId;
    @Enumerated(EnumType.STRING)
    @Column(name = "submission_source", length = 32, nullable = false)
    private HearingRoundSubmissionSource submissionSource;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submission_json", nullable = false, columnDefinition = "jsonb")
    private String submissionJson;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity()」。
    // 具体功能：「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity()」的上游创建点包括 「HearingRoundPartySubmissionEntity.submit」。
    // 下游影响：「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected HearingRoundPartySubmissionEntity() {}

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity(String)」。
    // 具体功能：「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity(String)」的上游创建点包括 「HearingRoundPartySubmissionEntity.submit」。
    // 下游影响：「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingRoundPartySubmissionEntity.HearingRoundPartySubmissionEntity(String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private HearingRoundPartySubmissionEntity(String id) {
        super(id);
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundPartySubmissionEntity.submit(String,String,String,int,ActorRole,String,HearingRoundSubmissionSource,String,Instant)」。
    // 具体功能：「HearingRoundPartySubmissionEntity.submit(String,String,String,int,ActorRole,String,HearingRoundSubmissionSource,String,Instant)」：提交庭审轮次当事方提交：先更新内部状态 「caseId」、「roundId」、「roundNo」、「participantRole」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「{}」，最终返回「HearingRoundPartySubmissionEntity」。
    // 上游调用：「HearingRoundPartySubmissionEntity.submit(String,String,String,int,ActorRole,String,HearingRoundSubmissionSource,String,Instant)」的上游调用点包括 「HearingRoundService.submitParty」、「HearingRoundService.recordPartyMessageSubmission」、「HearingRoundService.autoTimeoutSubmission」、「ActiveCourtroomContextAssemblerTest.submission」。
    // 下游影响：「HearingRoundPartySubmissionEntity.submit(String,String,String,int,ActorRole,String,HearingRoundSubmissionSource,String,Instant)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HearingRoundPartySubmissionEntity」交给调用方。
    // 系统意义：「HearingRoundPartySubmissionEntity.submit(String,String,String,int,ActorRole,String,HearingRoundSubmissionSource,String,Instant)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public static HearingRoundPartySubmissionEntity submit(
            String id,
            String caseId,
            String roundId,
            int roundNo,
            ActorRole participantRole,
            String participantId,
            HearingRoundSubmissionSource submissionSource,
            String submissionJson,
            Instant now) {
        if (participantRole != ActorRole.USER && participantRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("hearing round submission requires case party role");
        }
        HearingRoundPartySubmissionEntity entity =
                new HearingRoundPartySubmissionEntity(id);
        entity.caseId = caseId;
        entity.roundId = roundId;
        entity.roundNo = roundNo;
        entity.participantRole = participantRole;
        entity.participantId = participantId;
        entity.submissionSource = submissionSource;
        entity.submissionJson =
                submissionJson == null || submissionJson.isBlank()
                        ? "{}"
                        : submissionJson;
        entity.submittedAt = now;
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.createdBy = participantId;
        entity.updatedBy = participantId;
        return entity;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundPartySubmissionEntity.getParticipantRole()」。
    // 具体功能：「HearingRoundPartySubmissionEntity.getParticipantRole()」：读取「HearingRoundPartySubmissionEntity」中的「participantRole」状态，向 JPA、应用服务或序列化层返回「ActorRole」。
    // 上游调用：「HearingRoundPartySubmissionEntity.getParticipantRole()」的上游调用点包括 「ActiveCourtroomContextAssembler.submissionNode」。
    // 下游影响：「HearingRoundPartySubmissionEntity.getParticipantRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「HearingRoundPartySubmissionEntity.getParticipantRole()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public ActorRole getParticipantRole() {
        return participantRole;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundPartySubmissionEntity.getSubmissionSource()」。
    // 具体功能：「HearingRoundPartySubmissionEntity.getSubmissionSource()」：读取「HearingRoundPartySubmissionEntity」中的「submissionSource」状态，向 JPA、应用服务或序列化层返回「HearingRoundSubmissionSource」。
    // 上游调用：「HearingRoundPartySubmissionEntity.getSubmissionSource()」的上游调用点包括 「ActiveCourtroomContextAssembler.submissionNode」。
    // 下游影响：「HearingRoundPartySubmissionEntity.getSubmissionSource()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「HearingRoundSubmissionSource」交给调用方。
    // 系统意义：「HearingRoundPartySubmissionEntity.getSubmissionSource()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public HearingRoundSubmissionSource getSubmissionSource() {
        return submissionSource;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「HearingRoundPartySubmissionEntity.getSubmissionJson()」。
    // 具体功能：「HearingRoundPartySubmissionEntity.getSubmissionJson()」：读取「HearingRoundPartySubmissionEntity」中的「submissionJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「HearingRoundPartySubmissionEntity.getSubmissionJson()」的上游调用点包括 「ActiveCourtroomContextAssembler.submissionNode」。
    // 下游影响：「HearingRoundPartySubmissionEntity.getSubmissionJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingRoundPartySubmissionEntity.getSubmissionJson()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getSubmissionJson() {
        return submissionJson;
    }
}
