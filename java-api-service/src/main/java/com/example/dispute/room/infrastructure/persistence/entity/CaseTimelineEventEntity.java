/*
 * 所属模块：房间协作与权限。
 * 文件职责：映射案件时间线事件数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「getSequenceNo」、「getEventType」、「getRoomId」、「getEventJson」、「getAudienceJson」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

// 所属模块：【房间协作与权限 / JPA 实体层】类型「CaseTimelineEventEntity」。
// 类型职责：映射案件时间线事件数据库记录并保存可审计状态；本类型显式提供 「CaseTimelineEventEntity」、「CaseTimelineEventEntity」、「create」、「create」、「getSequenceNo」、「getEventType」。
// 协作关系：主要由 「CaseEventService.recordLifecycleEvent」、「CaseEventService.recordRoomMessage」、「CaseEventService.view」、「CaseEventService.visibleTo」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "case_timeline_event")
@Immutable
public class CaseTimelineEventEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;
    @Column(name = "dossier_id", length = 64)
    private String dossierId;
    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;
    @Column(name = "room_id", length = 64)
    private String roomId;
    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_refs_json", nullable = false, columnDefinition = "jsonb")
    private String sourceRefsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_json", nullable = false, columnDefinition = "jsonb")
    private String eventJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_json", nullable = false, columnDefinition = "jsonb")
    private String audienceJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_actor_ids_json", nullable = false, columnDefinition = "jsonb")
    private String audienceActorIdsJson;
    @Column(name = "event_key", length = 128)
    private String eventKey;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.CaseTimelineEventEntity()」。
    // 具体功能：「CaseTimelineEventEntity.CaseTimelineEventEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseTimelineEventEntity.CaseTimelineEventEntity()」的上游创建点包括 「CaseTimelineEventEntity.create」。
    // 下游影响：「CaseTimelineEventEntity.CaseTimelineEventEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseTimelineEventEntity.CaseTimelineEventEntity()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected CaseTimelineEventEntity() {}
    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.CaseTimelineEventEntity(String)」。
    // 具体功能：「CaseTimelineEventEntity.CaseTimelineEventEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「CaseTimelineEventEntity.CaseTimelineEventEntity(String)」的上游创建点包括 「CaseTimelineEventEntity.create」。
    // 下游影响：「CaseTimelineEventEntity.CaseTimelineEventEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseTimelineEventEntity.CaseTimelineEventEntity(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private CaseTimelineEventEntity(String id) { super(id); }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String)」。
    // 具体功能：「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String)」：提供「create」的便捷重载：接收 「id」(String)、「caseId」(String)、「roomId」(String)、「sequenceNo」(long)、「eventType」(String)、「eventTime」(Instant)、「sourceRefsJson」(String)、「eventJson」(String)、「audienceJson」(String)、「eventKey」(String)、「createdBy」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String)」的上游调用点包括 「CaseEventService.recordRoomMessage」、「CaseEventService.recordLifecycleEvent」、「CaseTimelineEventEntity.create」、「RoomMessageAndEventServiceTest.event」。
    // 下游影响：「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String)」向下依次触达 「create」；计算结果以「CaseTimelineEventEntity」交给调用方。
    // 系统意义：「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CaseTimelineEventEntity create(
            String id, String caseId, String roomId, long sequenceNo, String eventType,
            Instant eventTime, String sourceRefsJson, String eventJson, String audienceJson,
            String eventKey, String createdBy) {
        return create(
                id,
                caseId,
                roomId,
                sequenceNo,
                eventType,
                eventTime,
                sourceRefsJson,
                eventJson,
                audienceJson,
                "[]",
                eventKey,
                createdBy);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String,String)」。
    // 具体功能：「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String,String)」：创建案件时间线事件：先更新内部状态 「caseId」、「roomId」、「sequenceNo」、「eventType」，最终返回「CaseTimelineEventEntity」。
    // 上游调用：「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String,String)」的上游调用点包括 「CaseEventService.recordRoomMessage」、「CaseEventService.recordLifecycleEvent」、「CaseTimelineEventEntity.create」、「RoomMessageAndEventServiceTest.event」。
    // 下游影响：「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「CaseTimelineEventEntity」交给调用方。
    // 系统意义：「CaseTimelineEventEntity.create(String,String,String,long,String,Instant,String,String,String,String,String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static CaseTimelineEventEntity create(
            String id, String caseId, String roomId, long sequenceNo, String eventType,
            Instant eventTime, String sourceRefsJson, String eventJson, String audienceJson,
            String audienceActorIdsJson, String eventKey, String createdBy) {
        CaseTimelineEventEntity entity = new CaseTimelineEventEntity(id);
        entity.caseId = caseId;
        entity.roomId = roomId;
        entity.sequenceNo = sequenceNo;
        entity.eventType = eventType;
        entity.eventTime = eventTime;
        entity.sourceRefsJson = sourceRefsJson;
        entity.eventJson = eventJson;
        entity.audienceJson = audienceJson;
        entity.audienceActorIdsJson = audienceActorIdsJson;
        entity.eventKey = eventKey;
        entity.createdAt = eventTime;
        entity.createdBy = createdBy;
        return entity;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.getSequenceNo()」。
    // 具体功能：「CaseTimelineEventEntity.getSequenceNo()」：读取「CaseTimelineEventEntity」中的「sequenceNo」状态，向 JPA、应用服务或序列化层返回「long」。
    // 上游调用：「CaseTimelineEventEntity.getSequenceNo()」的上游调用点包括 「CaseEventService.view」。
    // 下游影响：「CaseTimelineEventEntity.getSequenceNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「long」交给调用方。
    // 系统意义：「CaseTimelineEventEntity.getSequenceNo()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public long getSequenceNo() { return sequenceNo; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.getEventType()」。
    // 具体功能：「CaseTimelineEventEntity.getEventType()」：读取「CaseTimelineEventEntity」中的「eventType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseTimelineEventEntity.getEventType()」的上游调用点包括 「CaseEventService.view」。
    // 下游影响：「CaseTimelineEventEntity.getEventType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseTimelineEventEntity.getEventType()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getEventType() { return eventType; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.getRoomId()」。
    // 具体功能：「CaseTimelineEventEntity.getRoomId()」：读取「CaseTimelineEventEntity」中的「roomId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseTimelineEventEntity.getRoomId()」的上游调用点包括 「CaseEventService.view」。
    // 下游影响：「CaseTimelineEventEntity.getRoomId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseTimelineEventEntity.getRoomId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getRoomId() { return roomId; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.getEventJson()」。
    // 具体功能：「CaseTimelineEventEntity.getEventJson()」：读取「CaseTimelineEventEntity」中的「eventJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseTimelineEventEntity.getEventJson()」的上游调用点包括 「CaseEventService.view」。
    // 下游影响：「CaseTimelineEventEntity.getEventJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseTimelineEventEntity.getEventJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getEventJson() { return eventJson; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.getAudienceJson()」。
    // 具体功能：「CaseTimelineEventEntity.getAudienceJson()」：读取「CaseTimelineEventEntity」中的「audienceJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseTimelineEventEntity.getAudienceJson()」的上游调用点包括 「CaseEventService.visibleTo」。
    // 下游影响：「CaseTimelineEventEntity.getAudienceJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseTimelineEventEntity.getAudienceJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAudienceJson() { return audienceJson; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.getAudienceActorIdsJson()」。
    // 具体功能：「CaseTimelineEventEntity.getAudienceActorIdsJson()」：读取「CaseTimelineEventEntity」中的「audienceActorIdsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「CaseTimelineEventEntity.getAudienceActorIdsJson()」的上游调用点包括 「CaseEventService.visibleTo」。
    // 下游影响：「CaseTimelineEventEntity.getAudienceActorIdsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseTimelineEventEntity.getAudienceActorIdsJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAudienceActorIdsJson() { return audienceActorIdsJson == null ? "[]" : audienceActorIdsJson; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「CaseTimelineEventEntity.getEventTime()」。
    // 具体功能：「CaseTimelineEventEntity.getEventTime()」：读取「CaseTimelineEventEntity」中的「eventTime」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「CaseTimelineEventEntity.getEventTime()」的上游调用点包括 「CaseEventService.view」。
    // 下游影响：「CaseTimelineEventEntity.getEventTime()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「CaseTimelineEventEntity.getEventTime()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public Instant getEventTime() { return eventTime; }
}
