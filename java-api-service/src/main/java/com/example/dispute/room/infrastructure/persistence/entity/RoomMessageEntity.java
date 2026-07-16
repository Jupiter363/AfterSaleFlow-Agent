/*
 * 所属模块：房间协作与权限。
 * 文件职责：映射房间消息数据库记录并保存可审计状态。
 * 业务链路：核心入口/契约为 「create」、「getCaseId」、「getRoomId」、「getSequenceNo」、「getSenderRole」、「getSenderId」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageSource;
import com.example.dispute.room.domain.MessageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

// 所属模块：【房间协作与权限 / JPA 实体层】类型「RoomMessageEntity」。
// 类型职责：映射房间消息数据库记录并保存可审计状态；本类型显式提供 「RoomMessageEntity」、「RoomMessageEntity」、「create」、「create」、「create」、「getCaseId」。
// 协作关系：主要由 「EvidenceAgentTurnService.appendAgentMessage」、「EvidenceAgentTurnService.isPartySender」、「EvidenceAgentTurnService.isSupersededOpeningMessage」、「EvidenceAgentTurnService.view」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Entity
@Table(name = "room_message")
@Immutable
public class RoomMessageEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "room_id", length = 64, nullable = false)
    private String roomId;

    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", length = 32, nullable = false)
    private MessageSenderType senderType;

    @Column(name = "sender_role", length = 64, nullable = false)
    private String senderRole;

    @Column(name = "sender_id", length = 128, nullable = false)
    private String senderId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_json", nullable = false, columnDefinition = "jsonb")
    private String audienceJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_actor_ids_json", nullable = false, columnDefinition = "jsonb")
    private String audienceActorIdsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", length = 64, nullable = false)
    private MessageType messageType;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_source", length = 32, nullable = false)
    private MessageSource messageSource;

    @Column(name = "message_text", columnDefinition = "text")
    private String messageText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachment_refs_json", nullable = false, columnDefinition = "jsonb")
    private String attachmentRefsJson;

    @Column(name = "agent_run_id", length = 64)
    private String agentRunId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.RoomMessageEntity()」。
    // 具体功能：「RoomMessageEntity.RoomMessageEntity()」：使用 无显式入参 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「RoomMessageEntity.RoomMessageEntity()」的上游创建点包括 「RoomMessageEntity.create」。
    // 下游影响：「RoomMessageEntity.RoomMessageEntity()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RoomMessageEntity.RoomMessageEntity()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    protected RoomMessageEntity() {}

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.RoomMessageEntity(String)」。
    // 具体功能：「RoomMessageEntity.RoomMessageEntity(String)」：使用 「id」(String) 创建 JPA 实体骨架并初始化主键；其余业务字段由显式工厂方法或 Hibernate 回填，避免无参构造被误当作完整业务对象。
    // 上游调用：「RoomMessageEntity.RoomMessageEntity(String)」的上游创建点包括 「RoomMessageEntity.create」。
    // 下游影响：「RoomMessageEntity.RoomMessageEntity(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RoomMessageEntity.RoomMessageEntity(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private RoomMessageEntity(String id) {
        super(id);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,MessageType,String,String,String,Instant,String)」。
    // 具体功能：「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,MessageType,String,String,String,Instant,String)」：提供「create」的便捷重载：接收 「id」(String)、「caseId」(String)、「roomId」(String)、「sequenceNo」(long)、「senderType」(MessageSenderType)、「senderRole」(String)、「senderId」(String)、「audienceJson」(String)、「messageType」(MessageType)、「messageText」(String)、「attachmentRefsJson」(String)、「idempotencyKey」(String)、「createdAt」(Instant)、「traceId」(String)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,MessageType,String,String,String,Instant,String)」的上游调用点包括 「HearingCourtBootstrapService.appendAgentMessageIfAbsent」、「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「HearingCourtOrchestrator.appendJudgeMessage」、「EvidenceAgentTurnService.appendAgentMessage」。
    // 下游影响：「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,MessageType,String,String,String,Instant,String)」向下依次触达 「create」；计算结果以「RoomMessageEntity」交给调用方。
    // 系统意义：「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,MessageType,String,String,String,Instant,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static RoomMessageEntity create(
            String id,
            String caseId,
            String roomId,
            long sequenceNo,
            MessageSenderType senderType,
            String senderRole,
            String senderId,
            String audienceJson,
            MessageType messageType,
            String messageText,
            String attachmentRefsJson,
            String idempotencyKey,
            Instant createdAt,
            String traceId) {
        return create(
                id,
                caseId,
                roomId,
                sequenceNo,
                senderType,
                senderRole,
                senderId,
                audienceJson,
                "[]",
                messageType,
                messageText,
                attachmentRefsJson,
                idempotencyKey,
                createdAt,
                traceId);
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,String,MessageType,String,String,String,Integer,Instant,String)」。
    // 具体功能：「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,String,MessageType,String,String,String,Integer,Instant,String)」：创建房间消息：先更新内部状态 「caseId」、「roomId」、「sequenceNo」、「senderType」；实际协作者为 「Objects.requireNonNull」、「required」；处理的关键状态/协议值包括 「id」、「caseId」、「roomId」、「senderRole」，最终返回「RoomMessageEntity」。
    // 上游调用：「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,String,MessageType,String,String,String,Integer,Instant,String)」的上游调用点包括 「HearingCourtBootstrapService.appendAgentMessageIfAbsent」、「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「HearingCourtOrchestrator.appendJudgeMessage」、「EvidenceAgentTurnService.appendAgentMessage」。
    // 下游影响：「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,String,MessageType,String,String,String,Integer,Instant,String)」向下依次触达 「Objects.requireNonNull」、「required」；计算结果以「RoomMessageEntity」交给调用方。
    // 系统意义：「RoomMessageEntity.create(String,String,String,long,MessageSenderType,String,String,String,String,MessageType,String,String,String,Integer,Instant,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public static RoomMessageEntity create(
            String id,
            String caseId,
            String roomId,
            long sequenceNo,
            MessageSenderType senderType,
            String senderRole,
            String senderId,
            String audienceJson,
            String audienceActorIdsJson,
            MessageType messageType,
            String messageText,
            String attachmentRefsJson,
            String idempotencyKey,
            Instant createdAt,
            String traceId) {
        return create(
                id,
                caseId,
                roomId,
                sequenceNo,
                senderType,
                senderRole,
                senderId,
                audienceJson,
                audienceActorIdsJson,
                defaultSource(senderType),
                messageType,
                messageText,
                attachmentRefsJson,
                idempotencyKey,
                createdAt,
                traceId);
    }

    public static RoomMessageEntity create(
            String id,
            String caseId,
            String roomId,
            long sequenceNo,
            MessageSenderType senderType,
            String senderRole,
            String senderId,
            String audienceJson,
            String audienceActorIdsJson,
            MessageSource messageSource,
            MessageType messageType,
            String messageText,
            String attachmentRefsJson,
            String idempotencyKey,
            Instant createdAt,
            String traceId) {
        RoomMessageEntity entity = new RoomMessageEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.roomId = required(roomId, "roomId");
        entity.sequenceNo = sequenceNo;
        entity.senderType = Objects.requireNonNull(senderType);
        entity.senderRole = required(senderRole, "senderRole");
        entity.senderId = required(senderId, "senderId");
        entity.audienceJson = required(audienceJson, "audienceJson");
        entity.audienceActorIdsJson = required(audienceActorIdsJson, "audienceActorIdsJson");
        entity.messageSource = Objects.requireNonNull(messageSource);
        entity.messageType = Objects.requireNonNull(messageType);
        entity.messageText = messageText;
        entity.attachmentRefsJson = required(attachmentRefsJson, "attachmentRefsJson");
        entity.idempotencyKey = required(idempotencyKey, "idempotencyKey");
        entity.createdAt = Objects.requireNonNull(createdAt);
        entity.traceId = traceId;
        entity.createdBy = senderId;
        return entity;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getCaseId()」。
    // 具体功能：「RoomMessageEntity.getCaseId()」：读取「RoomMessageEntity」中的「caseId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getCaseId()」的上游调用点包括 「EvidenceAgentTurnService.view」、「RoomMessageService.view」。
    // 下游影响：「RoomMessageEntity.getCaseId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getCaseId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getCaseId() { return caseId; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getRoomId()」。
    // 具体功能：「RoomMessageEntity.getRoomId()」：读取「RoomMessageEntity」中的「roomId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getRoomId()」的上游调用点包括 「EvidenceAgentTurnService.view」、「RoomMessageService.assertSameImmutableRequest」、「RoomMessageService.view」。
    // 下游影响：「RoomMessageEntity.getRoomId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getRoomId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getRoomId() { return roomId; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getSequenceNo()」。
    // 具体功能：「RoomMessageEntity.getSequenceNo()」：读取「RoomMessageEntity」中的「sequenceNo」状态，向 JPA、应用服务或序列化层返回「long」。
    // 上游调用：「RoomMessageEntity.getSequenceNo()」的上游调用点包括 「EvidenceAgentTurnService.view」、「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.dialogueMessage」、「RoomMessageService.view」。
    // 下游影响：「RoomMessageEntity.getSequenceNo()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「long」交给调用方。
    // 系统意义：「RoomMessageEntity.getSequenceNo()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public long getSequenceNo() { return sequenceNo; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getSenderRole()」。
    // 具体功能：「RoomMessageEntity.getSenderRole()」：读取「RoomMessageEntity」中的「senderRole」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getSenderRole()」的上游调用点包括 「EvidenceAgentTurnService.isSupersededOpeningMessage」、「EvidenceAgentTurnService.visibleToAccessSession」、「EvidenceAgentTurnService.view」、「EvidenceAgentTurnService.isPartySender」。
    // 下游影响：「RoomMessageEntity.getSenderRole()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getSenderRole()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getSenderRole() { return senderRole; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getSenderId()」。
    // 具体功能：「RoomMessageEntity.getSenderId()」：读取「RoomMessageEntity」中的「senderId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getSenderId()」的上游调用点包括 「EvidenceAgentTurnService.isSupersededOpeningMessage」、「EvidenceAgentTurnService.visibleToAccessSession」、「EvidenceAgentTurnService.view」、「RoomMessageService.assertSameImmutableRequest」。
    // 下游影响：「RoomMessageEntity.getSenderId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getSenderId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getSenderId() { return senderId; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getAudienceJson()」。
    // 具体功能：「RoomMessageEntity.getAudienceJson()」：读取「RoomMessageEntity」中的「audienceJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getAudienceJson()」的上游调用点包括 「EvidenceAgentTurnService.visibleToAccessSession」、「RoomMessageService.visibleTo」。
    // 下游影响：「RoomMessageEntity.getAudienceJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getAudienceJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAudienceJson() { return audienceJson; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getAudienceActorIdsJson()」。
    // 具体功能：「RoomMessageEntity.getAudienceActorIdsJson()」：读取「RoomMessageEntity」中的「audienceActorIdsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getAudienceActorIdsJson()」的上游调用点包括 「EvidenceAgentTurnService.visibleToAccessSession」、「RoomMessageService.visibleTo」。
    // 下游影响：「RoomMessageEntity.getAudienceActorIdsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getAudienceActorIdsJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAudienceActorIdsJson() { return audienceActorIdsJson == null ? "[]" : audienceActorIdsJson; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getMessageType()」。
    // 具体功能：「RoomMessageEntity.getMessageType()」：读取「RoomMessageEntity」中的「messageType」状态，向 JPA、应用服务或序列化层返回「MessageType」。
    // 上游调用：「RoomMessageEntity.getMessageType()」的上游调用点包括 「EvidenceAgentTurnService.isSupersededOpeningMessage」、「EvidenceAgentTurnService.view」、「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.dialogueMessage」。
    // 下游影响：「RoomMessageEntity.getMessageType()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「MessageType」交给调用方。
    // 系统意义：「RoomMessageEntity.getMessageType()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public MessageType getMessageType() { return messageType; }
    public MessageSource getMessageSource() { return messageSource; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getMessageText()」。
    // 具体功能：「RoomMessageEntity.getMessageText()」：读取「RoomMessageEntity」中的「messageText」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getMessageText()」的上游调用点包括 「EvidenceAgentTurnService.isSupersededOpeningMessage」、「EvidenceAgentTurnService.view」、「IntakeAgentTurnService.continueFromParticipantMessage」、「IntakeAgentTurnService.dialogueMessage」。
    // 下游影响：「RoomMessageEntity.getMessageText()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getMessageText()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getMessageText() { return messageText; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getAttachmentRefsJson()」。
    // 具体功能：「RoomMessageEntity.getAttachmentRefsJson()」：读取「RoomMessageEntity」中的「attachmentRefsJson」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getAttachmentRefsJson()」的上游调用点包括 「EvidenceAgentTurnService.view」、「RoomMessageService.view」。
    // 下游影响：「RoomMessageEntity.getAttachmentRefsJson()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getAttachmentRefsJson()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAttachmentRefsJson() { return attachmentRefsJson; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getAgentRunId()」。
    // 具体功能：「RoomMessageEntity.getAgentRunId()」：读取「RoomMessageEntity」中的「agentRunId」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getAgentRunId()」的上游调用点包括 「EvidenceAgentTurnService.view」、「RoomMessageService.view」。
    // 下游影响：「RoomMessageEntity.getAgentRunId()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getAgentRunId()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getAgentRunId() { return agentRunId; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getIdempotencyKey()」。
    // 具体功能：「RoomMessageEntity.getIdempotencyKey()」：读取「RoomMessageEntity」中的「idempotencyKey」状态，向 JPA、应用服务或序列化层返回「String」。
    // 上游调用：「RoomMessageEntity.getIdempotencyKey()」由使用「RoomMessageEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomMessageEntity.getIdempotencyKey()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.getIdempotencyKey()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public String getIdempotencyKey() { return idempotencyKey; }
    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.getCreatedAt()」。
    // 具体功能：「RoomMessageEntity.getCreatedAt()」：读取「RoomMessageEntity」中的「createdAt」状态，向 JPA、应用服务或序列化层返回「Instant」。
    // 上游调用：「RoomMessageEntity.getCreatedAt()」的上游调用点包括 「EvidenceAgentTurnService.view」、「RoomMessageService.view」。
    // 下游影响：「RoomMessageEntity.getCreatedAt()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Instant」交给调用方。
    // 系统意义：「RoomMessageEntity.getCreatedAt()」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public Instant getCreatedAt() { return createdAt; }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.attachAgentRun(String)」。
    // 具体功能：「RoomMessageEntity.attachAgentRun(String)」：按attachAgent运行：先更新内部状态 「agentRunId」；不满足前置条件时抛出 「IllegalArgumentException」、「IllegalStateException」，最终返回「void」。
    // 上游调用：「RoomMessageEntity.attachAgentRun(String)」由使用「RoomMessageEntity」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RoomMessageEntity.attachAgentRun(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RoomMessageEntity.attachAgentRun(String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public void attachAgentRun(String agentRunId) {
        if (agentRunId == null || agentRunId.isBlank()) {
            throw new IllegalArgumentException("agentRunId must not be blank");
        }
        if (this.agentRunId != null && !this.agentRunId.equals(agentRunId)) {
            throw new IllegalStateException("room message already belongs to another agent run");
        }
        this.agentRunId = agentRunId;
    }

    // 所属模块：【房间协作与权限 / JPA 实体层】「RoomMessageEntity.required(String,String)」。
    // 具体功能：「RoomMessageEntity.required(String,String)」：校验字符串；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「RoomMessageEntity.required(String,String)」的上游调用点包括 「RoomMessageEntity.create」。
    // 下游影响：「RoomMessageEntity.required(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RoomMessageEntity.required(String,String)」直接影响 PostgreSQL 事实投影；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static MessageSource defaultSource(MessageSenderType senderType) {
        return switch (Objects.requireNonNull(senderType)) {
            case PARTY -> MessageSource.PARTY_ACTION;
            case AGENT -> MessageSource.AGENT_LLM;
            case SYSTEM, REVIEWER -> MessageSource.SYSTEM_STAGE_EVENT;
        };
    }
}
