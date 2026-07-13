/*
 * 所属模块：共享小法庭。
 * 文件职责：映射AgentA2A消息数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「getCaseId」、「getRoundNo」、「getFromAgent」、「getToAgent」、「getMessageType」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 所属模块：【共享小法庭 / JPA 实体层】类型「AgentA2AMessageEntity」。
// 类型职责：映射AgentA2A消息数据库记录并保存可审计状态；本类型显式提供 「AgentA2AMessageEntity」、「AgentA2AMessageEntity」、「create」、「required」、「getCaseId」、「getRoundNo」。
// 协作关系：主要由 「AgentA2AMessageService.record」、「AgentA2AMessageService.view」、「AgentA2AMessageServiceTest.loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload」、「HearingPersistenceIntegrationTest.seedFinalCourtroomContext」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "agent_a2a_message")
public class AgentA2AMessageEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "round_no", nullable = false)
    private int roundNo;

    @Column(name = "from_agent", length = 64, nullable = false)
    private String fromAgent;

    @Column(name = "to_agent", length = 64, nullable = false)
    private String toAgent;

    @Column(name = "message_type", length = 64, nullable = false)
    private String messageType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_refs_json", nullable = false, columnDefinition = "jsonb")
    private String inputRefsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "visibility", length = 32, nullable = false)
    private String visibility;

    @Column(name = "agent_run_id", length = 64)
    private String agentRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.AgentA2AMessageEntity()」。
    // 具体功能：「AgentA2AMessageEntity.AgentA2AMessageEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AgentA2AMessageEntity.AgentA2AMessageEntity()」的上游创建点包括 「AgentA2AMessageEntity.create」。
    // 下游影响：「AgentA2AMessageEntity.AgentA2AMessageEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentA2AMessageEntity.AgentA2AMessageEntity()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected AgentA2AMessageEntity() {}

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.AgentA2AMessageEntity(String)」。
    // 具体功能：「AgentA2AMessageEntity.AgentA2AMessageEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「AgentA2AMessageEntity.AgentA2AMessageEntity(String)」的上游创建点包括 「AgentA2AMessageEntity.create」。
    // 下游影响：「AgentA2AMessageEntity.AgentA2AMessageEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentA2AMessageEntity.AgentA2AMessageEntity(String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private AgentA2AMessageEntity(String id) {
        super(id);
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.create(String,String,int,String,String,String,String,String,String,String,Instant,String)」。
    // 具体功能：「AgentA2AMessageEntity.create(String,String,int,String,String,String,String,String,String,String,Instant,String)」：创建AgentA2A消息：先更新内部状态 「caseId」、「roundNo」、「fromAgent」、「toAgent」；实际协作者为 「required」；不满足前置条件时抛出 「IllegalArgumentException」；处理的关键状态/协议值包括 「caseId」、「fromAgent」、「toAgent」、「messageType」，最终返回「AgentA2AMessageEntity」。
    // 上游调用：「AgentA2AMessageEntity.create(String,String,int,String,String,String,String,String,String,String,Instant,String)」的上游调用点包括 「AgentA2AMessageService.record」、「AgentA2AMessageServiceTest.loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload」、「HearingPersistenceIntegrationTest.seedFinalCourtroomContext」。
    // 下游影响：「AgentA2AMessageEntity.create(String,String,int,String,String,String,String,String,String,String,Instant,String)」向下依次触达 「required」；计算结果以「AgentA2AMessageEntity」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.create(String,String,int,String,String,String,String,String,String,String,Instant,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public static AgentA2AMessageEntity create(
            String id,
            String caseId,
            int roundNo,
            String fromAgent,
            String toAgent,
            String messageType,
            String inputRefsJson,
            String payloadJson,
            String visibility,
            String agentRunId,
            Instant now,
            String actorId) {
        if (roundNo < 1) {
            throw new IllegalArgumentException("A2A round number must be positive");
        }
        AgentA2AMessageEntity entity = new AgentA2AMessageEntity(id);
        entity.caseId = required(caseId, "caseId");
        entity.roundNo = roundNo;
        entity.fromAgent = required(fromAgent, "fromAgent");
        entity.toAgent = required(toAgent, "toAgent");
        entity.messageType = required(messageType, "messageType");
        entity.inputRefsJson = inputRefsJson == null || inputRefsJson.isBlank() ? "{}" : inputRefsJson;
        entity.payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        entity.visibility = required(visibility, "visibility");
        entity.agentRunId = agentRunId == null || agentRunId.isBlank() ? null : agentRunId;
        entity.createdAt = now;
        entity.createdBy = required(actorId, "actorId");
        return entity;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.required(String,String)」。
    // 具体功能：「AgentA2AMessageEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「AgentA2AMessageEntity.required(String,String)」的上游调用点包括 「AgentA2AMessageEntity.create」。
    // 下游影响：「AgentA2AMessageEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.required(String,String)」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getCaseId()」。
    // 具体功能：「AgentA2AMessageEntity.getCaseId()」：读取「AgentA2AMessageEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentA2AMessageEntity.getCaseId()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getCaseId()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getCaseId() {
        return caseId;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getRoundNo()」。
    // 具体功能：「AgentA2AMessageEntity.getRoundNo()」：读取「AgentA2AMessageEntity」中的「roundNo」状态，向 JPA、应用服务或序列化层返回「int」。
    // 上游调用：「AgentA2AMessageEntity.getRoundNo()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getRoundNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getRoundNo()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public int getRoundNo() {
        return roundNo;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getFromAgent()」。
    // 具体功能：「AgentA2AMessageEntity.getFromAgent()」：读取「AgentA2AMessageEntity」中的「fromAgent」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentA2AMessageEntity.getFromAgent()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getFromAgent()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getFromAgent()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getFromAgent() {
        return fromAgent;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getToAgent()」。
    // 具体功能：「AgentA2AMessageEntity.getToAgent()」：读取「AgentA2AMessageEntity」中的「toAgent」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentA2AMessageEntity.getToAgent()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getToAgent()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getToAgent()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getToAgent() {
        return toAgent;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getMessageType()」。
    // 具体功能：「AgentA2AMessageEntity.getMessageType()」：读取「AgentA2AMessageEntity」中的「messageType」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentA2AMessageEntity.getMessageType()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getMessageType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getMessageType()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getMessageType() {
        return messageType;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getInputRefsJson()」。
    // 具体功能：「AgentA2AMessageEntity.getInputRefsJson()」：读取「AgentA2AMessageEntity」中的「inputRefsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentA2AMessageEntity.getInputRefsJson()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getInputRefsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getInputRefsJson()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getInputRefsJson() {
        return inputRefsJson;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getPayloadJson()」。
    // 具体功能：「AgentA2AMessageEntity.getPayloadJson()」：读取「AgentA2AMessageEntity」中的「payloadJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentA2AMessageEntity.getPayloadJson()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getPayloadJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getPayloadJson()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getPayloadJson() {
        return payloadJson;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getVisibility()」。
    // 具体功能：「AgentA2AMessageEntity.getVisibility()」：读取「AgentA2AMessageEntity」中的「visibility」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentA2AMessageEntity.getVisibility()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getVisibility()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getVisibility()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getVisibility() {
        return visibility;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getAgentRunId()」。
    // 具体功能：「AgentA2AMessageEntity.getAgentRunId()」：读取「AgentA2AMessageEntity」中的「agentRunId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「AgentA2AMessageEntity.getAgentRunId()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getAgentRunId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getAgentRunId()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public String getAgentRunId() {
        return agentRunId;
    }

    // 所属模块：【共享小法庭 / JPA 实体层】「AgentA2AMessageEntity.getCreatedAt()」。
    // 具体功能：「AgentA2AMessageEntity.getCreatedAt()」：读取「AgentA2AMessageEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「AgentA2AMessageEntity.getCreatedAt()」的上游调用点包括 「AgentA2AMessageService.view」。
    // 下游影响：「AgentA2AMessageEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「AgentA2AMessageEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public Instant getCreatedAt() {
        return createdAt;
    }
}
