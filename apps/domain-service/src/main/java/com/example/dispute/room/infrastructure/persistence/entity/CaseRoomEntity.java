/*
 * 所属模块：房间协作与权限。
 * 文件职责：映射案件房间数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「open」、「closed」、「prePersist」、「preUpdate」、「getCaseId」、「getRoomType」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【房间协作与权限 / JPA 实体层】类型「CaseRoomEntity」。
// 类型职责：映射案件房间数据库记录并保存可审计状态；本类型显式提供 「CaseRoomEntity」、「CaseRoomEntity」、「open」、「closed」、「create」、「prePersist」。
// 协作关系：主要由 「CaseApplicationService.createNew」、「EvidenceAgentTurnService.appendAgentMessage」、「EvidenceCompletionService.sealEvidenceAndOpenHearing」、「EvidenceContextEnvelopeFactory.create」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "case_room")
public class CaseRoomEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 32, nullable = false)
    private RoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_status", length = 32, nullable = false)
    private RoomStatus roomStatus;

    @Column(name = "opened_at")
    private OffsetDateTime openedAt;

    @Column(name = "sealed_at")
    private OffsetDateTime sealedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;

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

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.CaseRoomEntity()」。
    // 具体功能：「CaseRoomEntity.CaseRoomEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseRoomEntity.CaseRoomEntity()」的上游创建点包括 「CaseRoomEntity.create」。
    // 下游影响：「CaseRoomEntity.CaseRoomEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseRoomEntity.CaseRoomEntity()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected CaseRoomEntity() {}

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.CaseRoomEntity(String)」。
    // 具体功能：「CaseRoomEntity.CaseRoomEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseRoomEntity.CaseRoomEntity(String)」的上游创建点包括 「CaseRoomEntity.create」。
    // 下游影响：「CaseRoomEntity.CaseRoomEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseRoomEntity.CaseRoomEntity(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private CaseRoomEntity(String id) {
        super(id);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.open(String,String,RoomType,OffsetDateTime,String)」。
    // 具体功能：「CaseRoomEntity.open(String,String,RoomType,OffsetDateTime,String)」：开放案件房间；实际协作者为 「create」，最终返回「CaseRoomEntity」。
    // 上游调用：「CaseRoomEntity.open(String,String,RoomType,OffsetDateTime,String)」的上游调用点包括 「ExternalCaseImportTransactionService.materializeCurrentRoom」、「CaseApplicationService.createNew」、「EvidenceCompletionService.sealEvidenceAndOpenHearing」、「IntakeRoomService.confirm」。
    // 下游影响：「CaseRoomEntity.open(String,String,RoomType,OffsetDateTime,String)」向下依次触达 「create」；计算结果以「CaseRoomEntity」交给调用方。
    // 系统意义：「CaseRoomEntity.open(String,String,RoomType,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CaseRoomEntity open(
            String id,
            String caseId,
            RoomType roomType,
            OffsetDateTime now,
            String actorId) {
        return create(id, caseId, roomType, RoomStatus.OPEN, now, null, actorId);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.closed(String,String,RoomType,OffsetDateTime,String)」。
    // 具体功能：「CaseRoomEntity.closed(String,String,RoomType,OffsetDateTime,String)」：关闭closed；实际协作者为 「create」，最终返回「CaseRoomEntity」。
    // 上游调用：「CaseRoomEntity.closed(String,String,RoomType,OffsetDateTime,String)」的上游调用点包括 「ExternalCaseImportTransactionService.materializeCurrentRoom」、「IntakeRoomService.confirm」、「IntakeRoomService.cancel」。
    // 下游影响：「CaseRoomEntity.closed(String,String,RoomType,OffsetDateTime,String)」向下依次触达 「create」；计算结果以「CaseRoomEntity」交给调用方。
    // 系统意义：「CaseRoomEntity.closed(String,String,RoomType,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CaseRoomEntity closed(
            String id,
            String caseId,
            RoomType roomType,
            OffsetDateTime now,
            String actorId) {
        return create(id, caseId, roomType, RoomStatus.CLOSED, now, now, actorId);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.create(String,String,RoomType,RoomStatus,OffsetDateTime,OffsetDateTime,String)」。
    // 具体功能：「CaseRoomEntity.create(String,String,RoomType,RoomStatus,OffsetDateTime,OffsetDateTime,String)」：创建案件房间：先更新内部状态 「caseId」、「roomType」、「roomStatus」、「openedAt」；实际协作者为 「Objects.requireNonNull」、「required」；处理的关键状态/协议值包括 「id」、「caseId」、「{}」、「actorId」，最终返回「CaseRoomEntity」。
    // 上游调用：「CaseRoomEntity.create(String,String,RoomType,RoomStatus,OffsetDateTime,OffsetDateTime,String)」的上游调用点包括 「CaseRoomEntity.open」、「CaseRoomEntity.closed」。
    // 下游影响：「CaseRoomEntity.create(String,String,RoomType,RoomStatus,OffsetDateTime,OffsetDateTime,String)」向下依次触达 「Objects.requireNonNull」、「required」；计算结果以「CaseRoomEntity」交给调用方。
    // 系统意义：「CaseRoomEntity.create(String,String,RoomType,RoomStatus,OffsetDateTime,OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static CaseRoomEntity create(
            String id,
            String caseId,
            RoomType roomType,
            RoomStatus roomStatus,
            OffsetDateTime openedAt,
            OffsetDateTime closedAt,
            String actorId) {
        CaseRoomEntity entity = new CaseRoomEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.roomType = Objects.requireNonNull(roomType, "roomType must not be null");
        entity.roomStatus = Objects.requireNonNull(roomStatus, "roomStatus must not be null");
        entity.openedAt = openedAt;
        entity.closedAt = closedAt;
        entity.metadataJson = "{}";
        entity.createdBy = required(actorId, "actorId");
        entity.updatedBy = actorId;
        return entity;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.prePersist()」。
    // 具体功能：「CaseRoomEntity.prePersist()」：在 JPA 首次 INSERT 前初始化 「now」、「createdAt」、「updatedAt」，保证即使调用方没有显式赋值，数据库中的审计字段也完整。
    // 上游调用：「CaseRoomEntity.prePersist()」由使用「CaseRoomEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseRoomEntity.prePersist()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseRoomEntity.prePersist()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.preUpdate()」。
    // 具体功能：「CaseRoomEntity.preUpdate()」：在 JPA 发出 UPDATE 前刷新 「updatedAt」，使每次实体状态变更都留下可靠的最后修改时间。
    // 上游调用：「CaseRoomEntity.preUpdate()」由使用「CaseRoomEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseRoomEntity.preUpdate()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseRoomEntity.preUpdate()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.getCaseId()」。
    // 具体功能：「CaseRoomEntity.getCaseId()」：读取「CaseRoomEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseRoomEntity.getCaseId()」由使用「CaseRoomEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseRoomEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseRoomEntity.getCaseId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.getRoomType()」。
    // 具体功能：「CaseRoomEntity.getRoomType()」：读取「CaseRoomEntity」中的「roomType」状态，向 JPA、应用服务或序列化层返回「RoomType」。
    // 上游调用：「CaseRoomEntity.getRoomType()」的上游调用点包括 「EvidenceAgentTurnService.appendAgentMessage」、「EvidenceContextEnvelopeFactory.create」、「RoomMessageService.create」、「RoomMessageService.hearingRoundForPartyMessage」。
    // 下游影响：「CaseRoomEntity.getRoomType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RoomType」交给调用方。
    // 系统意义：「CaseRoomEntity.getRoomType()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public RoomType getRoomType() {
        return roomType;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.getRoomStatus()」。
    // 具体功能：「CaseRoomEntity.getRoomStatus()」：读取「CaseRoomEntity」中的「roomStatus」状态，向 JPA、应用服务或序列化层返回「RoomStatus」。
    // 上游调用：「CaseRoomEntity.getRoomStatus()」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」、「RoomMessageService.create」、「EvidenceCompletionServiceTest.bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing」。
    // 下游影响：「CaseRoomEntity.getRoomStatus()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RoomStatus」交给调用方。
    // 系统意义：「CaseRoomEntity.getRoomStatus()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public RoomStatus getRoomStatus() {
        return roomStatus;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.seal(OffsetDateTime,String)」。
    // 具体功能：「CaseRoomEntity.seal(OffsetDateTime,String)」：更新seal：先更新内部状态 「roomStatus」、「sealedAt」、「updatedBy」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「CaseRoomEntity.seal(OffsetDateTime,String)」由使用「CaseRoomEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseRoomEntity.seal(OffsetDateTime,String)」向下依次触达 「required」。
    // 系统意义：「CaseRoomEntity.seal(OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void seal(OffsetDateTime now, String actorId) {
        if (roomStatus != RoomStatus.OPEN && roomStatus != RoomStatus.WAITING) {
            throw new IllegalStateException("room cannot be sealed from " + roomStatus);
        }
        roomStatus = RoomStatus.SEALED;
        sealedAt = now;
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.close(OffsetDateTime,String)」。
    // 具体功能：「CaseRoomEntity.close(OffsetDateTime,String)」：关闭案件房间：先更新内部状态 「roomStatus」、「closedAt」、「updatedBy」；实际协作者为 「Objects.requireNonNull」、「required」；处理的关键状态/协议值包括 「actorId」，最终返回「void」。
    // 上游调用：「CaseRoomEntity.close(OffsetDateTime,String)」由使用「CaseRoomEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「CaseRoomEntity.close(OffsetDateTime,String)」向下依次触达 「Objects.requireNonNull」、「required」。
    // 系统意义：「CaseRoomEntity.close(OffsetDateTime,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void close(OffsetDateTime now, String actorId) {
        if (roomStatus == RoomStatus.CLOSED) {
            return;
        }
        roomStatus = RoomStatus.CLOSED;
        closedAt = Objects.requireNonNull(now, "now must not be null");
        updatedBy = required(actorId, "actorId");
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseRoomEntity.required(String,String)」。
    // 具体功能：「CaseRoomEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「CaseRoomEntity.required(String,String)」的上游调用点包括 「CaseRoomEntity.create」、「CaseRoomEntity.seal」、「CaseRoomEntity.close」。
    // 下游影响：「CaseRoomEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseRoomEntity.required(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
