/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：映射证据当事方完成确认数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「completed」、「getParticipantRole」、「getDossierVersion」、「getCaseId」、「getCompletionStatus」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

// 所属模块：【证据与版本化卷宗 / JPA 实体层】类型「EvidencePartyCompletionEntity」。
// 类型职责：映射证据当事方完成确认数据库记录并保存可审计状态；本类型显式提供 「EvidencePartyCompletionEntity」、「EvidencePartyCompletionEntity」、「completed」、「getParticipantRole」、「getDossierVersion」、「getCaseId」。
// 协作关系：主要由 「EvidenceCompletionService.complete」、「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Immutable
@Table(name = "evidence_party_completion")
public class EvidencePartyCompletionEntity extends AbstractEntity {
    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", length = 32, nullable = false)
    private ActorRole participantRole;
    @Column(name = "participant_id", length = 128, nullable = false)
    private String participantId;
    @Column(name = "completion_status", length = 32, nullable = false)
    private String completionStatus;
    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity()」。
    // 具体功能：「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity()」的上游创建点包括 「EvidencePartyCompletionEntity.completed」。
    // 下游影响：「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected EvidencePartyCompletionEntity() {}
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity(String)」。
    // 具体功能：「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity(String)」的上游创建点包括 「EvidencePartyCompletionEntity.completed」。
    // 下游影响：「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidencePartyCompletionEntity.EvidencePartyCompletionEntity(String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private EvidencePartyCompletionEntity(String id) { super(id); }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidencePartyCompletionEntity.completed(String,String,int,ActorRole,String,String,Instant)」。
    // 具体功能：「EvidencePartyCompletionEntity.completed(String,String,int,ActorRole,String,String,Instant)」：完成完成：先更新内部状态 「caseId」、「dossierVersion」、「participantRole」、「participantId」；处理的关键状态/协议值包括 「COMPLETED」，最终返回「EvidencePartyCompletionEntity」。
    // 上游调用：「EvidencePartyCompletionEntity.completed(String,String,int,ActorRole,String,String,Instant)」的上游调用点包括 「EvidenceCompletionService.complete」、「EvidenceCompletionServiceTest.repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation」。
    // 下游影响：「EvidencePartyCompletionEntity.completed(String,String,int,ActorRole,String,String,Instant)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidencePartyCompletionEntity」交给调用方。
    // 系统意义：「EvidencePartyCompletionEntity.completed(String,String,int,ActorRole,String,String,Instant)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public static EvidencePartyCompletionEntity completed(
            String id, String caseId, int dossierVersion, ActorRole role,
            String participantId, String idempotencyKey, Instant now) {
        EvidencePartyCompletionEntity entity = new EvidencePartyCompletionEntity(id);
        entity.caseId = caseId;
        entity.dossierVersion = dossierVersion;
        entity.participantRole = role;
        entity.participantId = participantId;
        entity.completionStatus = "COMPLETED";
        entity.idempotencyKey = idempotencyKey;
        entity.completedAt = now;
        entity.createdAt = now;
        entity.createdBy = participantId;
        return entity;
    }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidencePartyCompletionEntity.getParticipantRole()」。
    // 具体功能：「EvidencePartyCompletionEntity.getParticipantRole()」：读取「EvidencePartyCompletionEntity」中的「participantRole」状态，向 JPA、应用服务或序列化层返回「ActorRole」。
    // 上游调用：「EvidencePartyCompletionEntity.getParticipantRole()」由使用「EvidencePartyCompletionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidencePartyCompletionEntity.getParticipantRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「EvidencePartyCompletionEntity.getParticipantRole()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public ActorRole getParticipantRole() { return participantRole; }
    public String getParticipantId() { return participantId; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidencePartyCompletionEntity.getDossierVersion()」。
    // 具体功能：「EvidencePartyCompletionEntity.getDossierVersion()」：读取「EvidencePartyCompletionEntity」中的「dossierVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「EvidencePartyCompletionEntity.getDossierVersion()」由使用「EvidencePartyCompletionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidencePartyCompletionEntity.getDossierVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「EvidencePartyCompletionEntity.getDossierVersion()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public int getDossierVersion() { return dossierVersion; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidencePartyCompletionEntity.getCaseId()」。
    // 具体功能：「EvidencePartyCompletionEntity.getCaseId()」：读取「EvidencePartyCompletionEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidencePartyCompletionEntity.getCaseId()」由使用「EvidencePartyCompletionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidencePartyCompletionEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidencePartyCompletionEntity.getCaseId()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getCaseId() { return caseId; }
    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidencePartyCompletionEntity.getCompletionStatus()」。
    // 具体功能：「EvidencePartyCompletionEntity.getCompletionStatus()」：读取「EvidencePartyCompletionEntity」中的「completionStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidencePartyCompletionEntity.getCompletionStatus()」由使用「EvidencePartyCompletionEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidencePartyCompletionEntity.getCompletionStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidencePartyCompletionEntity.getCompletionStatus()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getCompletionStatus() { return completionStatus; }
}
