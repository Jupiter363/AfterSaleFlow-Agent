/*
 * 所属模块：房间协作与权限。
 * 文件职责：映射案件接待卷宗数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「replaceWith」、「prePersist」、「preUpdate」、「getCaseId」、「getRoomType」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.RoomType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【房间协作与权限 / JPA 实体层】类型「CaseIntakeDossierEntity」。
// 类型职责：映射案件接待卷宗数据库记录并保存可审计状态；本类型显式提供 「CaseIntakeDossierEntity」、「CaseIntakeDossierEntity」、「create」、「replaceWith」、「prePersist」、「preUpdate」。
// 协作关系：主要由 「EvidenceContextEnvelopeFactory.intakeDossierSnapshot」、「IntakeAgentTurnService.upsertCurrentDossier」、「RoomTurnMemoryQueryService.intakeDossierView」、「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "case_intake_dossier")
public class CaseIntakeDossierEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false)
    private RoomType roomType;

    @Column(name = "dossier_version", nullable = false)
    private int dossierVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dossier_json", nullable = false, columnDefinition = "jsonb")
    private String dossierJson;

    @Column(name = "quality_score", nullable = false)
    private int qualityScore;

    @Column(name = "ready_for_next_step", nullable = false)
    private boolean readyForNextStep;

    @Column(name = "admission_recommendation", length = 32, nullable = false)
    private String admissionRecommendation;

    @Column(name = "source_turn_no", nullable = false)
    private int sourceTurnNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.CaseIntakeDossierEntity()」。
    // 具体功能：「CaseIntakeDossierEntity.CaseIntakeDossierEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseIntakeDossierEntity.CaseIntakeDossierEntity()」的上游创建点包括 「CaseIntakeDossierEntity.create」。
    // 下游影响：「CaseIntakeDossierEntity.CaseIntakeDossierEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseIntakeDossierEntity.CaseIntakeDossierEntity()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected CaseIntakeDossierEntity() {}

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.CaseIntakeDossierEntity(String)」。
    // 具体功能：「CaseIntakeDossierEntity.CaseIntakeDossierEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseIntakeDossierEntity.CaseIntakeDossierEntity(String)」的上游创建点包括 「CaseIntakeDossierEntity.create」。
    // 下游影响：「CaseIntakeDossierEntity.CaseIntakeDossierEntity(String)」向下依次触达 「required」。
    // 系统意义：「CaseIntakeDossierEntity.CaseIntakeDossierEntity(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private CaseIntakeDossierEntity(String id) {
        super(required(id, "id"));
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.create(String,String,RoomType,String,int,boolean,String,int,String)」。
    // 具体功能：「CaseIntakeDossierEntity.create(String,String,RoomType,String,int,boolean,String,int,String)」：创建案件接待卷宗：先更新内部状态 「caseId」、「roomType」、「dossierVersion」、「dossierJson」；实际协作者为 「Objects.requireNonNull」、「required」、「clampQuality」、「positive」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「caseId」、「dossierJson」、「admissionRecommendation」、「sourceTurnNo」，最终返回「CaseIntakeDossierEntity」。
    // 上游调用：「CaseIntakeDossierEntity.create(String,String,RoomType,String,int,boolean,String,int,String)」的上游调用点包括 「IntakeAgentTurnService.upsertCurrentDossier」、「HearingCourtBootstrapServiceTest.bootstrapsTheHearingByFreezingPriorDossiersAndAppendingOpeningMessages」、「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply」、「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt」。
    // 下游影响：「CaseIntakeDossierEntity.create(String,String,RoomType,String,int,boolean,String,int,String)」向下依次触达 「Objects.requireNonNull」、「required」、「clampQuality」、「positive」；计算结果以「CaseIntakeDossierEntity」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.create(String,String,RoomType,String,int,boolean,String,int,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CaseIntakeDossierEntity create(
            String id,
            String caseId,
            RoomType roomType,
            String dossierJson,
            int qualityScore,
            boolean readyForNextStep,
            String admissionRecommendation,
            int sourceTurnNo,
            String actorId) {
        if (roomType != RoomType.INTAKE) {
            throw new IllegalArgumentException("case intake dossier only supports INTAKE room");
        }
        CaseIntakeDossierEntity entity = new CaseIntakeDossierEntity(id);
        entity.caseId = required(caseId, "caseId");
        entity.roomType = Objects.requireNonNull(roomType, "roomType must not be null");
        entity.dossierVersion = 1;
        entity.dossierJson = required(dossierJson, "dossierJson");
        entity.qualityScore = clampQuality(qualityScore);
        entity.readyForNextStep = readyForNextStep;
        entity.admissionRecommendation = required(admissionRecommendation, "admissionRecommendation");
        entity.sourceTurnNo = positive(sourceTurnNo, "sourceTurnNo");
        entity.createdBy = required(actorId, "actorId");
        entity.updatedBy = entity.createdBy;
        return entity;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.replaceWith(String,int,boolean,String,int,String)」。
    // 具体功能：「CaseIntakeDossierEntity.replaceWith(String,int,boolean,String,int,String)」：更新replace包含：先更新内部状态 「dossierJson」、「qualityScore」、「readyForNextStep」、「admissionRecommendation」；实际协作者为 「required」、「clampQuality」、「positive」；处理的关键状态/协议值包括 「dossierJson」、「admissionRecommendation」、「sourceTurnNo」、「actorId」，最终返回「void」。
    // 上游调用：「CaseIntakeDossierEntity.replaceWith(String,int,boolean,String,int,String)」由使用「CaseIntakeDossierEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseIntakeDossierEntity.replaceWith(String,int,boolean,String,int,String)」向下依次触达 「required」、「clampQuality」、「positive」。
    // 系统意义：「CaseIntakeDossierEntity.replaceWith(String,int,boolean,String,int,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void replaceWith(
            String dossierJson,
            int qualityScore,
            boolean readyForNextStep,
            String admissionRecommendation,
            int sourceTurnNo,
            String actorId) {
        dossierVersion += 1;
        this.dossierJson = required(dossierJson, "dossierJson");
        this.qualityScore = clampQuality(qualityScore);
        this.readyForNextStep = readyForNextStep;
        this.admissionRecommendation = required(admissionRecommendation, "admissionRecommendation");
        this.sourceTurnNo = positive(sourceTurnNo, "sourceTurnNo");
        this.updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.prePersist()」。
    // 具体功能：「CaseIntakeDossierEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「CaseIntakeDossierEntity.prePersist()」由使用「CaseIntakeDossierEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseIntakeDossierEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseIntakeDossierEntity.prePersist()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.preUpdate()」。
    // 具体功能：「CaseIntakeDossierEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「CaseIntakeDossierEntity.preUpdate()」由使用「CaseIntakeDossierEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseIntakeDossierEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseIntakeDossierEntity.preUpdate()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.getCaseId()」。
    // 具体功能：「CaseIntakeDossierEntity.getCaseId()」：读取「CaseIntakeDossierEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseIntakeDossierEntity.getCaseId()」的上游调用点包括 「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「CaseIntakeDossierEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.getCaseId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.getRoomType()」。
    // 具体功能：「CaseIntakeDossierEntity.getRoomType()」：读取「CaseIntakeDossierEntity」中的「roomType」状态，向 JPA、应用服务或序列化层返回「RoomType」。
    // 上游调用：「CaseIntakeDossierEntity.getRoomType()」的上游调用点包括 「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「CaseIntakeDossierEntity.getRoomType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RoomType」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.getRoomType()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public RoomType getRoomType() {
        return roomType;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.getDossierVersion()」。
    // 具体功能：「CaseIntakeDossierEntity.getDossierVersion()」：读取「CaseIntakeDossierEntity」中的「dossierVersion」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「CaseIntakeDossierEntity.getDossierVersion()」的上游调用点包括 「EvidenceContextEnvelopeFactory.intakeDossierSnapshot」、「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「CaseIntakeDossierEntity.getDossierVersion()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.getDossierVersion()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public int getDossierVersion() {
        return dossierVersion;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.getDossierJson()」。
    // 具体功能：「CaseIntakeDossierEntity.getDossierJson()」：读取「CaseIntakeDossierEntity」中的「dossierJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseIntakeDossierEntity.getDossierJson()」的上游调用点包括 「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「CaseIntakeDossierEntity.getDossierJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.getDossierJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getDossierJson() {
        return dossierJson;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.getQualityScore()」。
    // 具体功能：「CaseIntakeDossierEntity.getQualityScore()」：读取「CaseIntakeDossierEntity」中的「qualityScore」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「CaseIntakeDossierEntity.getQualityScore()」的上游调用点包括 「EvidenceContextEnvelopeFactory.intakeDossierSnapshot」、「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「CaseIntakeDossierEntity.getQualityScore()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.getQualityScore()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public int getQualityScore() {
        return qualityScore;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.isReadyForNextStep()」。
    // 具体功能：「CaseIntakeDossierEntity.isReadyForNextStep()」：判断是否Ready面向下一Step，最终返回「boolean」。
    // 上游调用：「CaseIntakeDossierEntity.isReadyForNextStep()」的上游调用点包括 「EvidenceContextEnvelopeFactory.intakeDossierSnapshot」、「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「CaseIntakeDossierEntity.isReadyForNextStep()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.isReadyForNextStep()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public boolean isReadyForNextStep() {
        return readyForNextStep;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.getAdmissionRecommendation()」。
    // 具体功能：「CaseIntakeDossierEntity.getAdmissionRecommendation()」：读取「CaseIntakeDossierEntity」中的「admissionRecommendation」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseIntakeDossierEntity.getAdmissionRecommendation()」的上游调用点包括 「EvidenceContextEnvelopeFactory.intakeDossierSnapshot」、「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「CaseIntakeDossierEntity.getAdmissionRecommendation()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.getAdmissionRecommendation()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAdmissionRecommendation() {
        return admissionRecommendation;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.getSourceTurnNo()」。
    // 具体功能：「CaseIntakeDossierEntity.getSourceTurnNo()」：读取「CaseIntakeDossierEntity」中的「sourceTurnNo」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「CaseIntakeDossierEntity.getSourceTurnNo()」的上游调用点包括 「EvidenceContextEnvelopeFactory.intakeDossierSnapshot」、「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「CaseIntakeDossierEntity.getSourceTurnNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.getSourceTurnNo()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public int getSourceTurnNo() {
        return sourceTurnNo;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.getUpdatedAt()」。
    // 具体功能：「CaseIntakeDossierEntity.getUpdatedAt()」：读取「CaseIntakeDossierEntity」中的「updatedAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「CaseIntakeDossierEntity.getUpdatedAt()」的上游调用点包括 「EvidenceContextEnvelopeFactory.intakeDossierSnapshot」、「RoomTurnMemoryQueryService.intakeDossierView」。
    // 下游影响：「CaseIntakeDossierEntity.getUpdatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.getUpdatedAt()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.required(String,String)」。
    // 具体功能：「CaseIntakeDossierEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「CaseIntakeDossierEntity.required(String,String)」的上游调用点包括 「CaseIntakeDossierEntity.CaseIntakeDossierEntity」、「CaseIntakeDossierEntity.create」、「CaseIntakeDossierEntity.replaceWith」。
    // 下游影响：「CaseIntakeDossierEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.required(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.positive(int,String)」。
    // 具体功能：「CaseIntakeDossierEntity.positive(int,String)」：构建正整数；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「int」。
    // 上游调用：「CaseIntakeDossierEntity.positive(int,String)」的上游调用点包括 「CaseIntakeDossierEntity.create」、「CaseIntakeDossierEntity.replaceWith」。
    // 下游影响：「CaseIntakeDossierEntity.positive(int,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.positive(int,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static int positive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseIntakeDossierEntity.clampQuality(int)」。
    // 具体功能：「CaseIntakeDossierEntity.clampQuality(int)」：限制质量分；实际协作者为 「Math.max」、「Math.min」，最终返回「int」。
    // 上游调用：「CaseIntakeDossierEntity.clampQuality(int)」的上游调用点包括 「CaseIntakeDossierEntity.create」、「CaseIntakeDossierEntity.replaceWith」。
    // 下游影响：「CaseIntakeDossierEntity.clampQuality(int)」向下依次触达 「Math.max」、「Math.min」；计算结果以「int」交给调用方。
    // 系统意义：「CaseIntakeDossierEntity.clampQuality(int)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static int clampQuality(int value) {
        return Math.max(0, Math.min(100, value));
    }
}

