/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义证据上下文信封V1跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Versioned, authorization-filtered evidence context supplied by the Java business boundary.
 *
 * <p>This envelope contains persisted facts and raw participant data only. Prompt construction,
 * semantic fact mapping, evidence-gap analysis, and text fallbacks belong to the Python harness.
 */
// 所属模块：【房间协作与权限 / 应用编排层】类型「EvidenceContextEnvelopeV1」。
// 类型职责：定义证据上下文信封V1跨层传递时使用的不可变数据契约；本类型显式提供 「EvidenceContextEnvelopeV1」。
// 协作关系：主要由 「EvidenceContextEnvelopeFactory.create」、「RestClientEvidenceAgentTurnClientTest.command」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record EvidenceContextEnvelopeV1(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("captured_at") String capturedAt,
        @JsonProperty("case_snapshot") CaseSnapshot caseSnapshot,
        @JsonProperty("intake_dossier_snapshot") IntakeDossierSnapshot intakeDossierSnapshot,
        @JsonProperty("actor_snapshot") ActorSnapshot actorSnapshot,
        @JsonProperty("current_event") CurrentEvent currentEvent,
        @JsonProperty("visible_evidence") List<VisibleEvidence> visibleEvidence,
        @JsonProperty("private_conversation") PrivateConversation privateConversation,
        @JsonProperty("room_policy") RoomPolicy roomPolicy) {

    public static final String SCHEMA_VERSION = "evidence_context_envelope.v1";

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeV1.EvidenceContextEnvelopeV1(String,String,String,CaseSnapshot,IntakeDossierSnapshot,ActorSnapshot,CurrentEvent,List,PrivateConversation,RoomPolicy)」。
    // 具体功能：「EvidenceContextEnvelopeV1.EvidenceContextEnvelopeV1(String,String,String,CaseSnapshot,IntakeDossierSnapshot,ActorSnapshot,CurrentEvent,List,PrivateConversation,RoomPolicy)」：在不可变「EvidenceContextEnvelopeV1」写入组件前校验 「SCHEMA_VERSION」(String)、「schemaVersion」(String)、「capturedAt」(String)、「caseSnapshot」(CaseSnapshot)、「intakeDossierSnapshot」(IntakeDossierSnapshot)、「actorSnapshot」(ActorSnapshot)、「currentEvent」(CurrentEvent)、「visibleEvidence」(List)、「privateConversation」(PrivateConversation)、「roomPolicy」(RoomPolicy)，非法输入会抛出 「IllegalArgumentException」；并通过 「Objects.requireNonNull」 做标准化或防御性复制。
    // 上游调用：「EvidenceContextEnvelopeV1.EvidenceContextEnvelopeV1(String,String,String,CaseSnapshot,IntakeDossierSnapshot,ActorSnapshot,CurrentEvent,List,PrivateConversation,RoomPolicy)」的上游创建点包括 「EvidenceContextEnvelopeFactory.create」、「RestClientEvidenceAgentTurnClientTest.command」。
    // 下游影响：「EvidenceContextEnvelopeV1.EvidenceContextEnvelopeV1(String,String,String,CaseSnapshot,IntakeDossierSnapshot,ActorSnapshot,CurrentEvent,List,PrivateConversation,RoomPolicy)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「EvidenceContextEnvelopeV1.EvidenceContextEnvelopeV1(String,String,String,CaseSnapshot,IntakeDossierSnapshot,ActorSnapshot,CurrentEvent,List,PrivateConversation,RoomPolicy)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public EvidenceContextEnvelopeV1 {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported evidence context envelope schema");
        }
        Objects.requireNonNull(capturedAt, "capturedAt must not be null");
        Objects.requireNonNull(caseSnapshot, "caseSnapshot must not be null");
        Objects.requireNonNull(actorSnapshot, "actorSnapshot must not be null");
        Objects.requireNonNull(currentEvent, "currentEvent must not be null");
        Objects.requireNonNull(privateConversation, "privateConversation must not be null");
        Objects.requireNonNull(roomPolicy, "roomPolicy must not be null");
        visibleEvidence = visibleEvidence == null ? List.of() : List.copyOf(visibleEvidence);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】类型「CaseSnapshot」。
    // 类型职责：定义案件快照跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record CaseSnapshot(
            @JsonProperty("case_id") String caseId,
            @JsonProperty("case_version") long caseVersion,
            @JsonProperty("case_status") String caseStatus,
            @JsonProperty("case_type") String caseType,
            @JsonProperty("dispute_type") String disputeType,
            @JsonProperty("initiator_role") String initiatorRole,
            String title,
            String description,
            @JsonProperty("risk_level") String riskLevel,
            @JsonProperty("route_type") String routeType,
            @JsonProperty("order_id") String orderId,
            @JsonProperty("after_sale_id") String afterSaleId,
            @JsonProperty("logistics_id") String logisticsId,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_system") String sourceSystem,
            @JsonProperty("external_case_ref") String externalCaseRef,
            @JsonProperty("current_room") String currentRoom,
            @JsonProperty("current_deadline_at") String currentDeadlineAt) {}

    // 所属模块：【房间协作与权限 / 应用编排层】类型「IntakeDossierSnapshot」。
    // 类型职责：定义接待卷宗快照跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record IntakeDossierSnapshot(
            @JsonProperty("dossier_id") String dossierId,
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("dossier_version") int dossierVersion,
            @JsonProperty("source_turn_no") int sourceTurnNo,
            @JsonProperty("quality_score") int qualityScore,
            @JsonProperty("ready_for_next_step") boolean readyForNextStep,
            @JsonProperty("admission_recommendation") String admissionRecommendation,
            @JsonProperty("updated_at") String updatedAt,
            JsonNode payload) {}

    // 所属模块：【房间协作与权限 / 应用编排层】类型「ActorSnapshot」。
    // 类型职责：定义操作者快照跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record ActorSnapshot(
            @JsonProperty("actor_id") String actorId,
            @JsonProperty("actor_role") String actorRole,
            @JsonProperty("initiator_role") String initiatorRole,
            @JsonProperty("access_session_id") String accessSessionId,
            @JsonProperty("agent_session_id") String agentSessionId,
            @JsonProperty("conversation_scope") String conversationScope,
            @JsonProperty("prompt_profile_id") String promptProfileId,
            @JsonProperty("memory_policy_id") String memoryPolicyId) {}

    // 所属模块：【房间协作与权限 / 应用编排层】类型「CurrentEvent」。
    // 类型职责：定义Current事件跨层传递时使用的不可变数据契约；本类型显式提供 「CurrentEvent」。
    // 协作关系：主要由 「EvidenceContextEnvelopeFactory.create」、「RestClientEvidenceAgentTurnClientTest.command」 使用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record CurrentEvent(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("message_type") MessageType messageType,
            @JsonProperty("actor_id") String actorId,
            @JsonProperty("actor_role") String actorRole,
            String text,
            @JsonProperty("attachment_refs") List<String> attachmentRefs,
            @JsonProperty("turn_no") int turnNo,
            @JsonProperty("occurred_at") String occurredAt) {

        // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeV1.CurrentEvent.CurrentEvent(String,String,MessageType,String,String,String,List,int,String)」。
        // 具体功能：「EvidenceContextEnvelopeV1.CurrentEvent.CurrentEvent(String,String,MessageType,String,String,String,List,int,String)」：在不可变「CurrentEvent」写入组件前校验 「eventId」(String)、「eventType」(String)、「messageType」(MessageType)、「actorId」(String)、「actorRole」(String)、「text」(String)、「attachmentRefs」(List)、「turnNo」(int)、「occurredAt」(String)，非法输入会抛出 「IllegalArgumentException」；并通过 「Objects.requireNonNull」 做标准化或防御性复制。
        // 上游调用：「EvidenceContextEnvelopeV1.CurrentEvent.CurrentEvent(String,String,MessageType,String,String,String,List,int,String)」的上游创建点包括 「EvidenceContextEnvelopeFactory.create」、「RestClientEvidenceAgentTurnClientTest.command」。
        // 下游影响：「EvidenceContextEnvelopeV1.CurrentEvent.CurrentEvent(String,String,MessageType,String,String,String,List,int,String)」向下依次触达 「Objects.requireNonNull」。
        // 系统意义：「EvidenceContextEnvelopeV1.CurrentEvent.CurrentEvent(String,String,MessageType,String,String,String,List,int,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
        // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
        public CurrentEvent {
            attachmentRefs = attachmentRefs == null ? List.of() : List.copyOf(attachmentRefs);
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(eventType, "eventType must not be null");
            Objects.requireNonNull(messageType, "messageType must not be null");
            Objects.requireNonNull(actorId, "actorId must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            if (turnNo < 1) {
                throw new IllegalArgumentException("turnNo must be positive");
            }
            if ("ROOM_OPENING".equals(eventType)
                    && (messageType != MessageType.AGENT_MESSAGE
                            || text != null
                            || !attachmentRefs.isEmpty())) {
                throw new IllegalArgumentException("room opening must not contain party content");
            }
            if ("PARTY_MESSAGE".equals(eventType)
                    && messageType != MessageType.PARTY_TEXT
                    && messageType != MessageType.PARTY_EVIDENCE_REFERENCE) {
                throw new IllegalArgumentException("party event has an invalid message type");
            }
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】类型「VisibleEvidence」。
    // 类型职责：定义可见证据跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record VisibleEvidence(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("dossier_id") String dossierId,
            @JsonProperty("evidence_type") String evidenceType,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("submitted_by_role") String submittedByRole,
            @JsonProperty("submitted_by_id") String submittedById,
            @JsonProperty("original_filename") String originalFilename,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("file_size") Long fileSize,
            @JsonProperty("file_hash") String fileHash,
            @JsonProperty("parsed_text") String parsedText,
            @JsonProperty("parse_status") String parseStatus,
            String visibility,
            boolean desensitized,
            JsonNode metadata,
            JsonNode extraction,
            @JsonProperty("occurred_at") String occurredAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("submitted_at") String submittedAt,
            @JsonProperty("submission_status") String submissionStatus,
            @JsonProperty("submission_batch_id") String submissionBatchId,
            @JsonProperty("content_url") String contentUrl) {}

    // 所属模块：【房间协作与权限 / 应用编排层】类型「PrivateConversation」。
    // 类型职责：定义私有会话跨层传递时使用的不可变数据契约；本类型显式提供 「PrivateConversation」。
    // 协作关系：主要由 「EvidenceContextEnvelopeFactory.create」、「RestClientEvidenceAgentTurnClientTest.command」 使用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record PrivateConversation(
            @JsonProperty("agent_session_id") String agentSessionId,
            @JsonProperty("conversation_scope") String conversationScope,
            @JsonProperty("source_count") int sourceCount,
            boolean truncated,
            @JsonProperty("recent_turns") List<IntakeRecentTurn> recentTurns) {

        // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeV1.PrivateConversation.PrivateConversation(String,String,int,boolean,List)」。
        // 具体功能：「EvidenceContextEnvelopeV1.PrivateConversation.PrivateConversation(String,String,int,boolean,List)」：在不可变「PrivateConversation」写入组件前校验 「agentSessionId」(String)、「conversationScope」(String)、「sourceCount」(int)、「truncated」(boolean)、「recentTurns」(List)，非法输入会抛出 「IllegalArgumentException」。
        // 上游调用：「EvidenceContextEnvelopeV1.PrivateConversation.PrivateConversation(String,String,int,boolean,List)」的上游创建点包括 「EvidenceContextEnvelopeFactory.create」、「RestClientEvidenceAgentTurnClientTest.command」。
        // 下游影响：「EvidenceContextEnvelopeV1.PrivateConversation.PrivateConversation(String,String,int,boolean,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「EvidenceContextEnvelopeV1.PrivateConversation.PrivateConversation(String,String,int,boolean,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
        // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
        public PrivateConversation {
            recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
            if (sourceCount < recentTurns.size()) {
                throw new IllegalArgumentException("sourceCount cannot be smaller than recentTurns");
            }
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】类型「RoomPolicy」。
    // 类型职责：以确定性规则计算房间，输出可解释且可测试的决策；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record RoomPolicy(
            @JsonProperty("room_id") String roomId,
            @JsonProperty("room_type") RoomType roomType,
            @JsonProperty("room_status") String roomStatus,
            @JsonProperty("current_deadline_at") String currentDeadlineAt,
            @JsonProperty("initiator_role") String initiatorRole,
            @JsonProperty("initiator_evidence_required") boolean initiatorEvidenceRequired) {}
}
