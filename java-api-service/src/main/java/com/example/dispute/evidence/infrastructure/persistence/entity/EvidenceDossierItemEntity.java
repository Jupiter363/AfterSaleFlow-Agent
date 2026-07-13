/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：映射证据卷宗Item数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「snapshot」、「getEvidenceId」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【证据与版本化卷宗 / JPA 实体层】类型「EvidenceDossierItemEntity」。
// 类型职责：映射证据卷宗Item数据库记录并保存可审计状态；本类型显式提供 「EvidenceDossierItemEntity」、「EvidenceDossierItemEntity」、「snapshot」、「getEvidenceId」。
// 协作关系：主要由 「EvidenceDossierFreezer.createFrozen」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Immutable
@Table(name = "evidence_dossier_item")
public class EvidenceDossierItemEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "dossier_id", length = 64, nullable = false)
    private String dossierId;

    @Column(name = "evidence_id", length = 64, nullable = false)
    private String evidenceId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceSnapshotJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceDossierItemEntity.EvidenceDossierItemEntity()」。
    // 具体功能：「EvidenceDossierItemEntity.EvidenceDossierItemEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceDossierItemEntity.EvidenceDossierItemEntity()」的上游创建点包括 「EvidenceDossierItemEntity.snapshot」。
    // 下游影响：「EvidenceDossierItemEntity.EvidenceDossierItemEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierItemEntity.EvidenceDossierItemEntity()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected EvidenceDossierItemEntity() {}

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceDossierItemEntity.EvidenceDossierItemEntity(String)」。
    // 具体功能：「EvidenceDossierItemEntity.EvidenceDossierItemEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「EvidenceDossierItemEntity.EvidenceDossierItemEntity(String)」的上游创建点包括 「EvidenceDossierItemEntity.snapshot」。
    // 下游影响：「EvidenceDossierItemEntity.EvidenceDossierItemEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceDossierItemEntity.EvidenceDossierItemEntity(String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private EvidenceDossierItemEntity(String id) {
        super(id);
    }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceDossierItemEntity.snapshot(String,String,String,String,int,String,Instant,String)」。
    // 具体功能：「EvidenceDossierItemEntity.snapshot(String,String,String,String,int,String,Instant,String)」：更新快照：先更新内部状态 「caseId」、「dossierId」、「evidenceId」、「sequenceNo」，最终返回「EvidenceDossierItemEntity」。
    // 上游调用：「EvidenceDossierItemEntity.snapshot(String,String,String,String,int,String,Instant,String)」的上游调用点包括 「EvidenceDossierFreezer.createFrozen」。
    // 下游影响：「EvidenceDossierItemEntity.snapshot(String,String,String,String,int,String,Instant,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidenceDossierItemEntity」交给调用方。
    // 系统意义：「EvidenceDossierItemEntity.snapshot(String,String,String,String,int,String,Instant,String)」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public static EvidenceDossierItemEntity snapshot(
            String id,
            String caseId,
            String dossierId,
            String evidenceId,
            int sequenceNo,
            String evidenceSnapshotJson,
            Instant now,
            String actorId) {
        EvidenceDossierItemEntity entity = new EvidenceDossierItemEntity(id);
        entity.caseId = caseId;
        entity.dossierId = dossierId;
        entity.evidenceId = evidenceId;
        entity.sequenceNo = sequenceNo;
        entity.evidenceSnapshotJson = evidenceSnapshotJson;
        entity.createdAt = now;
        entity.createdBy = actorId;
        return entity;
    }

    // 所属模块：【证据与版本化卷宗 / JPA 实体层】「EvidenceDossierItemEntity.getEvidenceId()」。
    // 具体功能：「EvidenceDossierItemEntity.getEvidenceId()」：读取「EvidenceDossierItemEntity」中的「evidenceId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「EvidenceDossierItemEntity.getEvidenceId()」由使用「EvidenceDossierItemEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「EvidenceDossierItemEntity.getEvidenceId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceDossierItemEntity.getEvidenceId()」直接影响 PostgreSQL 事实投影；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    public String getEvidenceId() {
        return evidenceId;
    }
}
