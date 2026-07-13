/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：映射证据提交批次数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「submitted」、「attachRoomMessage」、「getCaseId」、「getActorRole」、「getActorId」、「getEvidenceIdsJson」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【证据与版本化卷宗 / JPA 实体层】类型「EvidenceSubmissionBatchEntity」。
// 类型职责：映射证据提交批次数据库记录并保存可审计状态；本类型显式提供 「EvidenceSubmissionBatchEntity」、「EvidenceSubmissionBatchEntity」、「submitted」、「attachRoomMessage」、「getCaseId」、「getActorRole」。
// 协作关系：主要由 「EvidenceSubmissionService.createSubmission」、「EvidenceSubmissionService.view」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "evidence_submission_batch")
public class EvidenceSubmissionBatchEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "actor_role", length = 32, nullable = false)
    private String actorRole;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_ids_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceIdsJson;

    @Column(name = "batch_note", columnDefinition = "text")
    private String batchNote;

    @Column(name = "submit_status", length = 32, nullable = false)
    private String submitStatus;

    @Column(name = "room_message_id", length = 64)
    private String roomMessageId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity()」的上游创建点包括 「EvidenceSubmissionBatchEntity.submitted」。
    // 下游影响：「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected EvidenceSubmissionBatchEntity() {}

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity(String)」。
    // 具体功能：「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity(String)」的上游创建点包括 「EvidenceSubmissionBatchEntity.submitted」。
    // 下游影响：「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceSubmissionBatchEntity.EvidenceSubmissionBatchEntity(String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private EvidenceSubmissionBatchEntity(String id) {
        super(id);
    }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.submitted(String,String,String,String,String,String,String,Instant)」。
    // 具体功能：「EvidenceSubmissionBatchEntity.submitted(String,String,String,String,String,String,String,Instant)」：提交submitted：先更新内部状态 「caseId」、「actorRole」、「actorId」、「evidenceIdsJson」；处理的关键状态/协议值包括 「SUBMITTED」，最终返回「EvidenceSubmissionBatchEntity」。
    // 上游调用：「EvidenceSubmissionBatchEntity.submitted(String,String,String,String,String,String,String,Instant)」的上游调用点包括 「EvidenceSubmissionService.createSubmission」。
    // 下游影响：「EvidenceSubmissionBatchEntity.submitted(String,String,String,String,String,String,String,Instant)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidenceSubmissionBatchEntity」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.submitted(String,String,String,String,String,String,String,Instant)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public static EvidenceSubmissionBatchEntity submitted(
            String id,
            String caseId,
            String actorRole,
            String actorId,
            String evidenceIdsJson,
            String batchNote,
            String idempotencyKey,
            Instant submittedAt) {
        EvidenceSubmissionBatchEntity entity = new EvidenceSubmissionBatchEntity(id);
        entity.caseId = caseId;
        entity.actorRole = actorRole;
        entity.actorId = actorId;
        entity.evidenceIdsJson = evidenceIdsJson;
        entity.batchNote = batchNote;
        entity.submitStatus = "SUBMITTED";
        entity.idempotencyKey = idempotencyKey;
        entity.submittedAt = submittedAt;
        entity.createdAt = submittedAt;
        entity.createdBy = actorId;
        return entity;
    }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.attachRoomMessage(String)」。
    // 具体功能：「EvidenceSubmissionBatchEntity.attachRoomMessage(String)」：按attach房间消息：先更新内部状态 「roomMessageId」，最终返回「void」。
    // 上游调用：「EvidenceSubmissionBatchEntity.attachRoomMessage(String)」由使用「EvidenceSubmissionBatchEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceSubmissionBatchEntity.attachRoomMessage(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceSubmissionBatchEntity.attachRoomMessage(String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public void attachRoomMessage(String roomMessageId) {
        this.roomMessageId = roomMessageId;
    }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.getCaseId()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.getCaseId()」：读取「EvidenceSubmissionBatchEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceSubmissionBatchEntity.getCaseId()」的上游调用点包括 「EvidenceSubmissionService.view」。
    // 下游影响：「EvidenceSubmissionBatchEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.getCaseId()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getCaseId() { return caseId; }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.getActorRole()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.getActorRole()」：读取「EvidenceSubmissionBatchEntity」中的「actorRole」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceSubmissionBatchEntity.getActorRole()」的上游调用点包括 「EvidenceSubmissionService.view」。
    // 下游影响：「EvidenceSubmissionBatchEntity.getActorRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.getActorRole()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getActorRole() { return actorRole; }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.getActorId()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.getActorId()」：读取「EvidenceSubmissionBatchEntity」中的「actorId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceSubmissionBatchEntity.getActorId()」的上游调用点包括 「EvidenceSubmissionService.view」。
    // 下游影响：「EvidenceSubmissionBatchEntity.getActorId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.getActorId()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getActorId() { return actorId; }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.getEvidenceIdsJson()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.getEvidenceIdsJson()」：读取「EvidenceSubmissionBatchEntity」中的「evidenceIdsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceSubmissionBatchEntity.getEvidenceIdsJson()」的上游调用点包括 「EvidenceSubmissionService.view」。
    // 下游影响：「EvidenceSubmissionBatchEntity.getEvidenceIdsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.getEvidenceIdsJson()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getEvidenceIdsJson() { return evidenceIdsJson; }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.getBatchNote()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.getBatchNote()」：读取「EvidenceSubmissionBatchEntity」中的「batchNote」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceSubmissionBatchEntity.getBatchNote()」的上游调用点包括 「EvidenceSubmissionService.view」。
    // 下游影响：「EvidenceSubmissionBatchEntity.getBatchNote()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.getBatchNote()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getBatchNote() { return batchNote; }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.getSubmitStatus()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.getSubmitStatus()」：读取「EvidenceSubmissionBatchEntity」中的「submitStatus」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceSubmissionBatchEntity.getSubmitStatus()」的上游调用点包括 「EvidenceSubmissionService.view」。
    // 下游影响：「EvidenceSubmissionBatchEntity.getSubmitStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.getSubmitStatus()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getSubmitStatus() { return submitStatus; }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.getRoomMessageId()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.getRoomMessageId()」：读取「EvidenceSubmissionBatchEntity」中的「roomMessageId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceSubmissionBatchEntity.getRoomMessageId()」由使用「EvidenceSubmissionBatchEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceSubmissionBatchEntity.getRoomMessageId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.getRoomMessageId()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getRoomMessageId() { return roomMessageId; }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.getIdempotencyKey()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.getIdempotencyKey()」：读取「EvidenceSubmissionBatchEntity」中的「idempotencyKey」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceSubmissionBatchEntity.getIdempotencyKey()」由使用「EvidenceSubmissionBatchEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceSubmissionBatchEntity.getIdempotencyKey()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.getIdempotencyKey()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getIdempotencyKey() { return idempotencyKey; }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceSubmissionBatchEntity.getSubmittedAt()」。
    // 具体功能：「EvidenceSubmissionBatchEntity.getSubmittedAt()」：读取「EvidenceSubmissionBatchEntity」中的「submittedAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「EvidenceSubmissionBatchEntity.getSubmittedAt()」的上游调用点包括 「EvidenceSubmissionService.view」。
    // 下游影响：「EvidenceSubmissionBatchEntity.getSubmittedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「EvidenceSubmissionBatchEntity.getSubmittedAt()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public Instant getSubmittedAt() { return submittedAt; }
}
