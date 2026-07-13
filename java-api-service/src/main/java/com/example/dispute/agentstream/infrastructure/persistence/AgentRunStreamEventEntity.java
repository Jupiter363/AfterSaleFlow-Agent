/*
 * 所属模块：Agent 流式运行。
 * 文件职责：映射Agent运行流事件数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「getAgentRunId」、「getSequenceNo」、「getEventType」、「getPayloadJson」、「getCreatedAt」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【Agent 流式运行 / 持久化适配层】类型「AgentRunStreamEventEntity」。
// 类型职责：映射Agent运行流事件数据库记录并保存可审计状态；本类型显式提供 「AgentRunStreamEventEntity」、「AgentRunStreamEventEntity」、「create」、「getAgentRunId」、「getSequenceNo」、「getEventType」。
// 协作关系：主要由 「AgentRunStreamEventService.append」、「AgentRunStreamEventService.view」、「AgentRunStreamEventServiceTest.event」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Immutable
@Table(name = "agent_run_stream_event")
public class AgentRunStreamEventEntity extends AbstractEntity {

    @Column(name = "agent_run_id", length = 64, nullable = false)
    private String agentRunId;

    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;

    @Column(name = "event_type", length = 32, nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventEntity.AgentRunStreamEventEntity()」。
    // 具体功能：「AgentRunStreamEventEntity.AgentRunStreamEventEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AgentRunStreamEventEntity.AgentRunStreamEventEntity()」的上游创建点包括 「AgentRunStreamEventEntity.create」。
    // 下游影响：「AgentRunStreamEventEntity.AgentRunStreamEventEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunStreamEventEntity.AgentRunStreamEventEntity()」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected AgentRunStreamEventEntity() {}

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventEntity.AgentRunStreamEventEntity(String)」。
    // 具体功能：「AgentRunStreamEventEntity.AgentRunStreamEventEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AgentRunStreamEventEntity.AgentRunStreamEventEntity(String)」的上游创建点包括 「AgentRunStreamEventEntity.create」。
    // 下游影响：「AgentRunStreamEventEntity.AgentRunStreamEventEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunStreamEventEntity.AgentRunStreamEventEntity(String)」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private AgentRunStreamEventEntity(String id) {
        super(id);
    }

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventEntity.create(String,String,long,String,String)」。
    // 具体功能：「AgentRunStreamEventEntity.create(String,String,long,String,String)」：创建Agent运行流事件：先更新内部状态 「agentRunId」、「sequenceNo」、「eventType」、「payloadJson」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「agentRunId」、「eventType」、「payloadJson」、「agent-stream」，最终返回「AgentRunStreamEventEntity」。
    // 上游调用：「AgentRunStreamEventEntity.create(String,String,long,String,String)」的上游调用点包括 「AgentRunStreamEventService.append」、「AgentRunStreamEventServiceTest.event」。
    // 下游影响：「AgentRunStreamEventEntity.create(String,String,long,String,String)」向下依次触达 「required」；计算结果以「AgentRunStreamEventEntity」交给调用方。
    // 系统意义：「AgentRunStreamEventEntity.create(String,String,long,String,String)」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    public static AgentRunStreamEventEntity create(
            String id,
            String agentRunId,
            long sequenceNo,
            String eventType,
            String payloadJson) {
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("sequenceNo must not be negative");
        }
        AgentRunStreamEventEntity event = new AgentRunStreamEventEntity(id);
        event.agentRunId = required(agentRunId, "agentRunId");
        event.sequenceNo = sequenceNo;
        event.eventType = required(eventType, "eventType");
        event.payloadJson = required(payloadJson, "payloadJson");
        event.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        event.createdBy = "agent-stream";
        return event;
    }

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventEntity.getAgentRunId()」。
    // 具体功能：「AgentRunStreamEventEntity.getAgentRunId()」：读取「AgentRunStreamEventEntity」中的「agentRunId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunStreamEventEntity.getAgentRunId()」的上游调用点包括 「AgentRunStreamEventService.view」。
    // 下游影响：「AgentRunStreamEventEntity.getAgentRunId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunStreamEventEntity.getAgentRunId()」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    public String getAgentRunId() {
        return agentRunId;
    }

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventEntity.getSequenceNo()」。
    // 具体功能：「AgentRunStreamEventEntity.getSequenceNo()」：读取「AgentRunStreamEventEntity」中的「sequenceNo」状态，向 JPA、应用服务或序列化层返回「long」。
    // 上游调用：「AgentRunStreamEventEntity.getSequenceNo()」的上游调用点包括 「AgentRunStreamEventService.view」。
    // 下游影响：「AgentRunStreamEventEntity.getSequenceNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「long」交给调用方。
    // 系统意义：「AgentRunStreamEventEntity.getSequenceNo()」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    public long getSequenceNo() {
        return sequenceNo;
    }

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventEntity.getEventType()」。
    // 具体功能：「AgentRunStreamEventEntity.getEventType()」：读取「AgentRunStreamEventEntity」中的「eventType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunStreamEventEntity.getEventType()」的上游调用点包括 「AgentRunStreamEventService.view」。
    // 下游影响：「AgentRunStreamEventEntity.getEventType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunStreamEventEntity.getEventType()」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    public String getEventType() {
        return eventType;
    }

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventEntity.getPayloadJson()」。
    // 具体功能：「AgentRunStreamEventEntity.getPayloadJson()」：读取「AgentRunStreamEventEntity」中的「payloadJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentRunStreamEventEntity.getPayloadJson()」的上游调用点包括 「AgentRunStreamEventService.view」。
    // 下游影响：「AgentRunStreamEventEntity.getPayloadJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunStreamEventEntity.getPayloadJson()」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    public String getPayloadJson() {
        return payloadJson;
    }

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventEntity.getCreatedAt()」。
    // 具体功能：「AgentRunStreamEventEntity.getCreatedAt()」：读取「AgentRunStreamEventEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「OffsetDateTime」。
    // 上游调用：「AgentRunStreamEventEntity.getCreatedAt()」的上游调用点包括 「AgentRunStreamEventService.view」。
    // 下游影响：「AgentRunStreamEventEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OffsetDateTime」交给调用方。
    // 系统意义：「AgentRunStreamEventEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // 所属模块：【Agent 流式运行 / 持久化适配层】「AgentRunStreamEventEntity.required(String,String)」。
    // 具体功能：「AgentRunStreamEventEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「AgentRunStreamEventEntity.required(String,String)」的上游调用点包括 「AgentRunStreamEventEntity.create」。
    // 下游影响：「AgentRunStreamEventEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunStreamEventEntity.required(String,String)」直接影响 PostgreSQL 事实投影；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
