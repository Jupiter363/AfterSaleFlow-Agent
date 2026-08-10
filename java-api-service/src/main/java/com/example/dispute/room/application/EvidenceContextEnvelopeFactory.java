/*
 * 所属模块：房间协作与权限。
 * 文件职责：承载证据上下文信封工厂在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「create」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.FrozenIntakeSubmissionAuthority;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// 所属模块：【房间协作与权限 / 应用编排层】类型「EvidenceContextEnvelopeFactory」。
// 类型职责：承载证据上下文信封工厂在当前业务模块中的规则与协作边界；本类型显式提供 「EvidenceContextEnvelopeFactory」、「create」、「caseSnapshot」、「intakeDossierSnapshot」、「actorSnapshot」、「visibleEvidence」。
// 协作关系：主要由 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」、「EvidenceAgentTurnServiceTest.setUp」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class EvidenceContextEnvelopeFactory {

    private static final int RECENT_TURN_LIMIT = 20;

    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final CaseProcessProjectionRepository processProjectionRepository;
    private final CaseTimelineEventRepository timelineEventRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final RoomTurnMemoryRepository memoryRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.EvidenceContextEnvelopeFactory(CaseIntakeDossierRepository,EvidenceItemRepository,RoomTurnMemoryRepository,ObjectMapper,Clock)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.EvidenceContextEnvelopeFactory(CaseIntakeDossierRepository,EvidenceItemRepository,RoomTurnMemoryRepository,ObjectMapper,Clock)」：通过构造器接收 「intakeDossierRepository」(CaseIntakeDossierRepository)、「evidenceItemRepository」(EvidenceItemRepository)、「memoryRepository」(RoomTurnMemoryRepository)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「EvidenceContextEnvelopeFactory」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「EvidenceContextEnvelopeFactory.EvidenceContextEnvelopeFactory(CaseIntakeDossierRepository,EvidenceItemRepository,RoomTurnMemoryRepository,ObjectMapper,Clock)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「EvidenceAgentTurnServiceTest.setUp」 显式创建。
    // 下游影响：「EvidenceContextEnvelopeFactory.EvidenceContextEnvelopeFactory(CaseIntakeDossierRepository,EvidenceItemRepository,RoomTurnMemoryRepository,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceContextEnvelopeFactory.EvidenceContextEnvelopeFactory(CaseIntakeDossierRepository,EvidenceItemRepository,RoomTurnMemoryRepository,ObjectMapper,Clock)」负责主链路中的“证据上下文信封工厂”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public EvidenceContextEnvelopeFactory(
            CaseIntakeDossierRepository intakeDossierRepository,
            EvidenceItemRepository evidenceItemRepository,
            RoomTurnMemoryRepository memoryRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this(
                intakeDossierRepository,
                null,
                null,
                evidenceItemRepository,
                memoryRepository,
                objectMapper,
                clock);
    }

    @Autowired
    public EvidenceContextEnvelopeFactory(
            CaseIntakeDossierRepository intakeDossierRepository,
            CaseProcessProjectionRepository processProjectionRepository,
            CaseTimelineEventRepository timelineEventRepository,
            EvidenceItemRepository evidenceItemRepository,
            RoomTurnMemoryRepository memoryRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.intakeDossierRepository = intakeDossierRepository;
        this.processProjectionRepository = processProjectionRepository;
        this.timelineEventRepository = timelineEventRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.memoryRepository = memoryRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.create(FulfillmentCaseEntity,CaseRoomEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity,String,String,MessageType,String,List,int,Instant)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.create(FulfillmentCaseEntity,CaseRoomEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity,String,String,MessageType,String,List,int,Instant)」：创建证据上下文信封V1；实际协作者为 「intakeDossierRepository.findByCaseIdAndRoomType」、「dispute.getId」、「intakeDossier.getDossierJson」、「clock.instant」，最终返回「EvidenceContextEnvelopeV1」。
    // 上游调用：「EvidenceContextEnvelopeFactory.create(FulfillmentCaseEntity,CaseRoomEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity,String,String,MessageType,String,List,int,Instant)」的上游调用点包括 「EvidenceAgentTurnService.continueFromParticipantMessage」、「EvidenceAgentTurnService.ensureOpening」、「EvidenceAgentTurnService.ensureOpeningOrStart」。
    // 下游影响：「EvidenceContextEnvelopeFactory.create(FulfillmentCaseEntity,CaseRoomEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity,String,String,MessageType,String,List,int,Instant)」向下依次触达 「intakeDossierRepository.findByCaseIdAndRoomType」、「dispute.getId」、「intakeDossier.getDossierJson」、「clock.instant」；计算结果以「EvidenceContextEnvelopeV1」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.create(FulfillmentCaseEntity,CaseRoomEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity,String,String,MessageType,String,List,int,Instant)」负责主链路中的“证据上下文信封V1”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public EvidenceContextEnvelopeV1 create(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            AuthenticatedActor actor,
            CaseAccessSessionEntity accessSession,
            AgentConversationSessionEntity agentSession,
            String eventType,
            String eventId,
            MessageType messageType,
            String text,
            List<String> attachmentRefs,
            int turnNo,
            Instant occurredAt) {
        return create(
                dispute,
                room,
                actor,
                accessSession,
                agentSession,
                eventType,
                eventId,
                messageType,
                text,
                attachmentRefs,
                turnNo,
                occurredAt,
                resolveFrozenSubmission(dispute, room));
    }

    EvidenceContextEnvelopeV1 create(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            AuthenticatedActor actor,
            CaseAccessSessionEntity accessSession,
            AgentConversationSessionEntity agentSession,
            String eventType,
            String eventId,
            MessageType messageType,
            String text,
            List<String> attachmentRefs,
            int turnNo,
            Instant occurredAt,
            EvidenceContextEnvelopeV1.FrozenSubmission frozenSubmission) {
        CaseIntakeDossierEntity intakeDossier = null;
        JsonNode sharedIntakeDossierJson;
        if (frozenSubmission == null) {
            intakeDossier =
                    intakeDossierRepository
                            .findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE)
                            .orElse(null);
            JsonNode intakeDossierJson =
                    intakeDossier == null ? null : readJson(intakeDossier.getDossierJson());
            sharedIntakeDossierJson = sharedIntakeDossierProjection(intakeDossierJson);
        } else {
            sharedIntakeDossierJson = frozenMatrixProjection(frozenSubmission.matrix());
        }
        RecentTurnsWindow recentTurns = recentTurns(agentSession);
        List<EvidenceContextEnvelopeV1.VisibleEvidence> visibleEvidence =
                visibleEvidence(dispute.getId(), actor);
        validateEvidenceReferences(attachmentRefs, visibleEvidence);

        return new EvidenceContextEnvelopeV1(
                frozenSubmission == null
                        ? EvidenceContextEnvelopeV1.SCHEMA_VERSION
                        : EvidenceContextEnvelopeV1.FROZEN_SUBMISSION_SCHEMA_VERSION,
                clock.instant().toString(),
                caseSnapshot(dispute, sharedIntakeDossierJson),
                frozenSubmission == null
                        ? intakeDossierSnapshot(intakeDossier, sharedIntakeDossierJson)
                        : null,
                actorSnapshot(dispute, actor, accessSession, agentSession),
                new EvidenceContextEnvelopeV1.CurrentEvent(
                        eventId,
                        eventType,
                        messageType,
                        actor.actorId(),
                        actor.role().name(),
                        text,
                        attachmentRefs,
                        turnNo,
                        occurredAt.toString()),
                visibleEvidence,
                new EvidenceContextEnvelopeV1.PrivateConversation(
                        agentSession.getId(),
                        agentSession.getConversationScope(),
                        recentTurns.sourceCount(),
                        recentTurns.truncated(),
                        recentTurns.turns()),
                new EvidenceContextEnvelopeV1.RoomPolicy(
                        room.getId(),
                        room.getRoomType(),
                        room.getRoomStatus().name(),
                        isoTimestamp(dispute.getCurrentDeadlineAt()),
                        dispute.getInitiatorRole().name(),
                        true),
                frozenSubmission);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」：构建案件快照；实际协作者为 「dispute.getId」、「dispute.getVersion」、「dispute.getCaseStatus」、「dispute.getCaseType」，最终返回「EvidenceContextEnvelopeV1.CaseSnapshot」。
    // 上游调用：「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」。
    // 下游影响：「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」向下依次触达 「dispute.getId」、「dispute.getVersion」、「dispute.getCaseStatus」、「dispute.getCaseType」；计算结果以「EvidenceContextEnvelopeV1.CaseSnapshot」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」负责主链路中的“案件快照”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    EvidenceContextEnvelopeV1.FrozenSubmission resolveFrozenSubmission(
            FulfillmentCaseEntity dispute, CaseRoomEntity room) {
        Objects.requireNonNull(dispute, "dispute must not be null");
        Objects.requireNonNull(room, "room must not be null");
        if (room.getRoomType() != RoomType.EVIDENCE) {
            return null;
        }
        if (processProjectionRepository == null || timelineEventRepository == null) {
            return null;
        }
        CaseProcessProjectionEntity projection =
                processProjectionRepository.findById(dispute.getId()).orElse(null);
        if (projection == null) {
            return null;
        }
        String projectionRef = projection.getProjectionRef();
        String projectionSha256 = projection.getProjectionSha256();
        if (projectionRef == null && projectionSha256 == null) {
            return null;
        }
        try {
            if (projectionRef == null
                    || projectionSha256 == null
                    || !dispute.getId().equals(room.getCaseId())
                    || !dispute.getId().equals(projection.getCaseId())
                    || !"EVIDENCE".equals(projection.getCurrentRoom())
                    || projection.getRoomEpoch() < 0
                    || projection.getFencingToken() < 1) {
                throw new IllegalArgumentException(
                        "Evidence projection does not contain one exact frozen pair");
            }
            String pointer = "#" + FrozenIntakeSubmissionAuthority.FROZEN_MATRIX_RESULT_POINTER;
            if (!projectionRef.endsWith(pointer)) {
                throw new IllegalArgumentException(
                        "Evidence projection does not locate a frozen Submit matrix");
            }
            String submitEventRef =
                    projectionRef.substring(0, projectionRef.length() - pointer.length());
            String eventRefPrefix = "urn:after-sale-flow:intake-event:";
            if (!submitEventRef.startsWith(eventRefPrefix)
                    || submitEventRef.length() == eventRefPrefix.length()) {
                throw new IllegalArgumentException("frozen Submit event reference is invalid");
            }
            String submitEventId = submitEventRef.substring(eventRefPrefix.length());
            CaseTimelineEventEntity storedEvent =
                    timelineEventRepository
                            .findByIdAndCaseId(submitEventId, dispute.getId())
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "frozen Submit event is missing"));
            JsonNode eventDocument = objectMapper.readTree(storedEvent.getEventJson());
            if (!(eventDocument instanceof ObjectNode eventObject)) {
                throw new IllegalArgumentException("frozen Submit event must be an object");
            }
            ObjectNode eventHashInput = eventObject.deepCopy();
            JsonNode eventHash = eventHashInput.remove("event_hash");
            JsonNode result = eventObject.required("result");
            JsonNode frozenNode = result.required("frozen_submission");
            if (!(result instanceof ObjectNode)
                    || !(frozenNode instanceof ObjectNode frozenObject)
                    || frozenObject.size() != 2
                    || !frozenObject.path("authority").isObject()
                    || !frozenObject.path("matrix").isObject()
                    || eventHash == null
                    || !eventHash.isTextual()
                    || !eventHash.textValue().equals(ContractJson.sha256Hex(eventHashInput))
                    || !eventObject.required("result_hash")
                            .asText()
                            .equals(ContractJson.sha256Hex(result))) {
                throw new IllegalArgumentException(
                        "frozen Submit event canonical hashes are invalid");
            }
            JsonNode authorityDocument = frozenObject.required("authority");
            JsonNode matrix = frozenObject.required("matrix");
            FrozenIntakeSubmissionAuthority authority =
                    objectMapper.treeToValue(
                            authorityDocument, FrozenIntakeSubmissionAuthority.class);
            if (!ContractJson.canonicalString(authorityDocument)
                    .equals(
                            ContractJson.canonicalString(
                                    objectMapper.valueToTree(authority)))) {
                throw new IllegalArgumentException(
                        "frozen Submit authority serialization is not canonical");
            }
            authority.requireProjectionPair(projectionRef, projectionSha256);
            authority.requireMatchesMatrix(matrix);
            if (!storedEvent.getId().equals(authority.submitEventId())
                    || storedEvent.getSequenceNo() != authority.submitEventSequence()
                    || !storedEvent.getEventType().equals(authority.submitEventType())
                    || !"intake-branch-committed-event.v1"
                            .equals(eventObject.required("schema_version").asText())
                    || !authority.submitEventId()
                            .equals(eventObject.required("event_id").asText())
                    || !authority.submitEventRef()
                            .equals(eventObject.required("event_ref").asText())
                    || authority.submitEventSequence()
                            != eventObject.required("event_sequence").asLong()
                    || !authority.submitEventType()
                            .equals(eventObject.required("event_type").asText())
                    || !"RESPONDENT".equals(eventObject.required("party").asText())
                    || !authority.submitCommandId()
                            .equals(eventObject.required("command_id").asText())
                    || !authority.tenantSurrogate()
                            .equals(eventObject.required("tenant_surrogate").asText())
                    || !authority.caseId().equals(eventObject.required("case_id").asText())
                    || authority.sourceRoomEpoch()
                            != eventObject.required("room_epoch").asLong()
                    || authority.sourceFencingToken()
                            != eventObject.required("fencing_token").asLong()
                    || !authority.submitOperationKey()
                            .equals(eventObject.required("operation_key").asText())
                    || !authority.submitRequestHash()
                            .equals(eventObject.required("request_hash").asText())
                    || authority.sourceProcessRevision()
                            != eventObject.required("process_revision").asLong()
                    || authority.sourceRoomRevision()
                            != eventObject.required("room_revision").asLong()
                    || !"intake-branch-result.v2"
                            .equals(result.required("schema_version").asText())
                    || !authority.submitOperation()
                            .equals(result.required("operation").asText())
                    || !authority.caseId().equals(result.required("case_id").asText())
                    || authority.sourceProcessRevision()
                            != result.required("process_revision").asLong()
                    || authority.sourceRoomRevision()
                            != result.required("room_revision").asLong()
                    || !FrozenIntakeSubmissionAuthority.MATRIX_KIND.equals(
                            result.required("matrix_kind").asText())
                    || !authority.matrixContentHash()
                            .equals(result.required("matrix_hash").asText())
                    || !authority.tenantSurrogate().equals(projection.getTenantSurrogate())) {
                throw new IllegalArgumentException(
                        "frozen Submit event differs from its persisted authority");
            }
            return new EvidenceContextEnvelopeV1.FrozenSubmission(
                    projection.getRoomEpoch(),
                    projection.getFencingToken(),
                    projectionRef,
                    projectionSha256,
                    authority,
                    matrix);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "frozen Evidence submission authority is invalid", failure);
        }
    }

    void requireCurrentFrozenSubmission(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            EvidenceContextEnvelopeV1 envelope) {
        EvidenceContextEnvelopeV1.FrozenSubmission current =
                resolveFrozenSubmission(dispute, room);
        if (!envelope.freezeBound()) {
            if (current != null) {
                throw new IllegalStateException(
                        "legacy Evidence submission became freeze-bound before Agent finalization");
            }
            return;
        }
        if (!Objects.equals(current, envelope.frozenSubmission())) {
            throw new IllegalStateException(
                    "frozen Evidence submission changed before Agent finalization");
        }
    }

    private JsonNode frozenMatrixProjection(JsonNode matrix) {
        ObjectNode projected = objectMapper.createObjectNode();
        projected.put("schema_version", "intake_frozen_matrix_projection.v1");
        projected.set("case_fact_matrix", matrix.deepCopy());
        JsonNode overview = matrix.path("case_overview");
        ObjectNode story = projected.putObject("case_story");
        story.put("title", "待核验争议");
        story.put("one_sentence_summary", overview.path("neutral_summary").asText(""));
        ObjectNode coreState = projected.putObject("dispute_core_state");
        coreState.put("core_conflict", overview.path("core_conflict").asText(""));
        return projected;
    }

    private EvidenceContextEnvelopeV1.CaseSnapshot caseSnapshot(
            FulfillmentCaseEntity dispute, JsonNode sharedIntakeDossier) {
        String sharedDescription = sharedCaseDescription(dispute, sharedIntakeDossier);
        return new EvidenceContextEnvelopeV1.CaseSnapshot(
                dispute.getId(),
                dispute.getVersion(),
                dispute.getCaseStatus().name(),
                dispute.getCaseType(),
                dispute.getDisputeType(),
                dispute.getInitiatorRole().name(),
                dispute.getTitle(),
                sharedDescription,
                dispute.getRiskLevel().name(),
                dispute.getRouteType() == null ? null : dispute.getRouteType().name(),
                dispute.getOrderId(),
                dispute.getAfterSaleId(),
                dispute.getLogisticsId(),
                dispute.getSourceType().name(),
                dispute.getSourceSystem(),
                dispute.getExternalCaseRef(),
                dispute.getCurrentRoom(),
                isoTimestamp(dispute.getCurrentDeadlineAt()));
    }

    /**
     * Evidence turns consume the bilateral handoff, not either party's private intake transcript.
     * Keep the formal fact coordinate system and neutral summary while dropping raw statements,
     * handoff remarks and role-private intake fields before the AgentRun request is persisted.
     */
    private JsonNode sharedIntakeDossierProjection(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        JsonNode bilateralMatrix = payload.path("case_fact_matrix");
        if (!"case_fact_matrix.v2".equals(
                bilateralMatrix.path("schema_version").asText())) {
            return legacySharedIntakeProjection(payload);
        }

        ObjectNode projected = objectMapper.createObjectNode();
        projected.put(
                "schema_version",
                payload.path("schema_version").asText("intake_case_detail.v1"));
        copyObjectField(payload, projected, "references");
        projected.set("case_fact_matrix", bilateralMatrix.deepCopy());

        JsonNode overview = bilateralMatrix.path("case_overview");
        ObjectNode story = projected.putObject("case_story");
        story.put("title", "待核验争议");
        story.put(
                "one_sentence_summary",
                overview.path("neutral_summary").asText(""));
        ObjectNode coreState = projected.putObject("dispute_core_state");
        coreState.put("core_conflict", overview.path("core_conflict").asText(""));
        return projected;
    }

    private JsonNode legacySharedIntakeProjection(JsonNode payload) {
        ObjectNode projected = objectMapper.createObjectNode();
        if (payload.has("schema_version")) {
            projected.set("schema_version", payload.get("schema_version").deepCopy());
        }
        for (String field :
                List.of(
                        "references",
                        "case_story",
                        "dispute_focus",
                        "dispute_core_state",
                        "unilateral_case_matrix")) {
            copyObjectField(payload, projected, field);
        }
        return projected;
    }

    private static void copyObjectField(
            JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isObject()) {
            target.set(field, value.deepCopy());
        }
    }

    private static String sharedCaseDescription(
            FulfillmentCaseEntity dispute, JsonNode sharedIntakeDossier) {
        String neutralSummary =
                sharedIntakeDossier == null
                        ? ""
                        : sharedIntakeDossier
                                .path("case_story")
                                .path("one_sentence_summary")
                                .asText("")
                                .trim();
        return neutralSummary.isBlank() ? dispute.getDescription() : neutralSummary;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.intakeDossierSnapshot(CaseIntakeDossierEntity,JsonNode)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.intakeDossierSnapshot(CaseIntakeDossierEntity,JsonNode)」：构建接待卷宗快照；实际协作者为 「intakeDossier.getId」、「intakeDossier.getDossierVersion」、「intakeDossier.getSourceTurnNo」、「intakeDossier.getQualityScore」；处理的关键状态/协议值包括 「schema_version」，最终返回「EvidenceContextEnvelopeV1.IntakeDossierSnapshot」。
    // 上游调用：「EvidenceContextEnvelopeFactory.intakeDossierSnapshot(CaseIntakeDossierEntity,JsonNode)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」。
    // 下游影响：「EvidenceContextEnvelopeFactory.intakeDossierSnapshot(CaseIntakeDossierEntity,JsonNode)」向下依次触达 「intakeDossier.getId」、「intakeDossier.getDossierVersion」、「intakeDossier.getSourceTurnNo」、「intakeDossier.getQualityScore」；计算结果以「EvidenceContextEnvelopeV1.IntakeDossierSnapshot」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.intakeDossierSnapshot(CaseIntakeDossierEntity,JsonNode)」负责主链路中的“接待卷宗快照”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static EvidenceContextEnvelopeV1.IntakeDossierSnapshot intakeDossierSnapshot(
            CaseIntakeDossierEntity intakeDossier, JsonNode payload) {
        if (intakeDossier == null) {
            return null;
        }
        String payloadSchemaVersion =
                payload == null ? null : payload.path("schema_version").asText(null);
        return new EvidenceContextEnvelopeV1.IntakeDossierSnapshot(
                intakeDossier.getId(),
                payloadSchemaVersion,
                intakeDossier.getDossierVersion(),
                intakeDossier.getSourceTurnNo(),
                intakeDossier.getQualityScore(),
                intakeDossier.isReadyForNextStep(),
                intakeDossier.getAdmissionRecommendation(),
                isoTimestamp(intakeDossier.getUpdatedAt()),
                payload);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.actorSnapshot(FulfillmentCaseEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.actorSnapshot(FulfillmentCaseEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity)」：解析操作者快照；实际协作者为 「actor.actorId」、「actor.role」、「dispute.getInitiatorRole」、「accessSession.getId」，最终返回「EvidenceContextEnvelopeV1.ActorSnapshot」。
    // 上游调用：「EvidenceContextEnvelopeFactory.actorSnapshot(FulfillmentCaseEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」。
    // 下游影响：「EvidenceContextEnvelopeFactory.actorSnapshot(FulfillmentCaseEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity)」向下依次触达 「actor.actorId」、「actor.role」、「dispute.getInitiatorRole」、「accessSession.getId」；计算结果以「EvidenceContextEnvelopeV1.ActorSnapshot」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.actorSnapshot(FulfillmentCaseEntity,AuthenticatedActor,CaseAccessSessionEntity,AgentConversationSessionEntity)」负责主链路中的“快照”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static EvidenceContextEnvelopeV1.ActorSnapshot actorSnapshot(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor actor,
            CaseAccessSessionEntity accessSession,
            AgentConversationSessionEntity agentSession) {
        return new EvidenceContextEnvelopeV1.ActorSnapshot(
                actor.actorId(),
                actor.role().name(),
                dispute.getInitiatorRole().name(),
                accessSession.getId(),
                agentSession.getId(),
                agentSession.getConversationScope(),
                agentSession.getPromptProfileId(),
                agentSession.getMemoryPolicyId());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.visibleEvidence(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.visibleEvidence(String,AuthenticatedActor)」：提供「visibleEvidence」的便捷重载：接收 「caseId」(String)、「actor」(AuthenticatedActor)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「EvidenceContextEnvelopeFactory.visibleEvidence(String,AuthenticatedActor)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceContextEnvelopeFactory.visibleEvidence(String,AuthenticatedActor)」向下依次触达 「findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「visibleEvidenceTo」、「visibleEvidence」；计算结果以「List<EvidenceContextEnvelopeV1.VisibleEvidence>」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.visibleEvidence(String,AuthenticatedActor)」负责主链路中的“证据”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<EvidenceContextEnvelopeV1.VisibleEvidence> visibleEvidence(
            String caseId, AuthenticatedActor actor) {
        return evidenceItemRepository
                .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(caseId)
                .stream()
                .filter(item -> visibleEvidenceTo(item, actor))
                .map(item -> visibleEvidence(caseId, item))
                .toList();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.visibleEvidence(String,EvidenceItemEntity)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.visibleEvidence(String,EvidenceItemEntity)」：判断可见性证据；实际协作者为 「item.getId」、「item.getDossierId」、「item.getEvidenceType」、「item.getSourceType」，最终返回「EvidenceContextEnvelopeV1.VisibleEvidence」。
    // 上游调用：「EvidenceContextEnvelopeFactory.visibleEvidence(String,EvidenceItemEntity)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceContextEnvelopeFactory.visibleEvidence(String,EvidenceItemEntity)」向下依次触达 「item.getId」、「item.getDossierId」、「item.getEvidenceType」、「item.getSourceType」；计算结果以「EvidenceContextEnvelopeV1.VisibleEvidence」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.visibleEvidence(String,EvidenceItemEntity)」负责主链路中的“证据”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private EvidenceContextEnvelopeV1.VisibleEvidence visibleEvidence(
            String caseId, EvidenceItemEntity item) {
        return new EvidenceContextEnvelopeV1.VisibleEvidence(
                item.getId(),
                item.getDossierId(),
                item.getEvidenceType(),
                item.getSourceType(),
                item.getSubmittedByRole(),
                item.getSubmittedById(),
                item.getOriginalFilename(),
                item.getContentType(),
                item.getFileSize(),
                item.getFileHash(),
                item.getParsedText(),
                item.getParseStatus().name(),
                item.getVisibility(),
                item.isDesensitized(),
                readJson(item.getMetadataJson()),
                readJson(item.getExtractionJson()),
                isoTimestamp(item.getOccurredAt()),
                isoTimestamp(item.getCreatedAt()),
                isoTimestamp(item.getSubmittedAt()),
                item.getSubmissionStatus().name(),
                item.getSubmissionBatchId(),
                "/api/disputes/" + caseId + "/evidence/" + item.getId() + "/content");
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.validateEvidenceReferences(List,List)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.validateEvidenceReferences(List,List)」：校验证据References；实际协作者为 「Collectors.toSet」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「EvidenceContextEnvelopeFactory.validateEvidenceReferences(List,List)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」。
    // 下游影响：「EvidenceContextEnvelopeFactory.validateEvidenceReferences(List,List)」向下依次触达 「Collectors.toSet」。
    // 系统意义：「EvidenceContextEnvelopeFactory.validateEvidenceReferences(List,List)」在“证据References”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static void validateEvidenceReferences(
            List<String> attachmentRefs,
            List<EvidenceContextEnvelopeV1.VisibleEvidence> visibleEvidence) {
        if (attachmentRefs == null || attachmentRefs.isEmpty()) {
            return;
        }
        Set<String> visibleEvidenceIds =
                visibleEvidence.stream()
                        .map(EvidenceContextEnvelopeV1.VisibleEvidence::evidenceId)
                        .collect(Collectors.toSet());
        List<String> unauthorizedRefs =
                attachmentRefs.stream()
                        .filter(ref -> !visibleEvidenceIds.contains(ref))
                        .toList();
        if (!unauthorizedRefs.isEmpty()) {
            throw new ForbiddenException(
                    "evidence references are not visible to the current actor");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.recentTurns(AgentConversationSessionEntity)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.recentTurns(AgentConversationSessionEntity)」：构建最近轮对话；实际协作者为 「memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc」、「Comparator.comparingInt」、「agentSession.getId」、「memory.getAgentSessionId」，最终返回「RecentTurnsWindow」。
    // 上游调用：「EvidenceContextEnvelopeFactory.recentTurns(AgentConversationSessionEntity)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」。
    // 下游影响：「EvidenceContextEnvelopeFactory.recentTurns(AgentConversationSessionEntity)」向下依次触达 「memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc」、「Comparator.comparingInt」、「agentSession.getId」、「memory.getAgentSessionId」；计算结果以「RecentTurnsWindow」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.recentTurns(AgentConversationSessionEntity)」负责主链路中的“最近轮对话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private RecentTurnsWindow recentTurns(AgentConversationSessionEntity agentSession) {
        List<RoomTurnMemoryEntity> memories =
                memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(agentSession.getId());
        List<IntakeRecentTurn> scopedTurns =
                memories.stream()
                        .filter(
                                memory ->
                                        agentSession
                                                .getId()
                                                .equals(memory.getAgentSessionId()))
                        .sorted(Comparator.comparingInt(RoomTurnMemoryEntity::getTurnNo))
                        .map(
                                memory ->
                                        new IntakeRecentTurn(
                                                memory.getTurnNo(),
                                                memory.getActorId(),
                                                memory.getAnswerRole(),
                                                memory.getAnswerContent(),
                                                memory.getAgentRole(),
                                                memory.getAgentResponse(),
                                                readJson(memory.getScrollSnapshotJson()),
                                                memory.getAgentSessionId(),
                                                memory.getConversationScope()))
                        .toList();
        if (scopedTurns.size() <= RECENT_TURN_LIMIT) {
            return new RecentTurnsWindow(scopedTurns.size(), false, scopedTurns);
        }
        return new RecentTurnsWindow(
                scopedTurns.size(),
                true,
                scopedTurns.subList(
                        scopedTurns.size() - RECENT_TURN_LIMIT, scopedTurns.size()));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.visibleEvidenceTo(EvidenceItemEntity,AuthenticatedActor)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.visibleEvidenceTo(EvidenceItemEntity,AuthenticatedActor)」：判断可见性证据；实际协作者为 「actor.role」、「item.getVisibility」、「item.getSubmittedByRole」、「actor.actorId」；处理的关键状态/协议值包括 「PARTIES」、「PLATFORM」，最终返回「boolean」。
    // 上游调用：「EvidenceContextEnvelopeFactory.visibleEvidenceTo(EvidenceItemEntity,AuthenticatedActor)」的上游调用点包括 「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceContextEnvelopeFactory.visibleEvidenceTo(EvidenceItemEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「item.getVisibility」、「item.getSubmittedByRole」、「actor.actorId」；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.visibleEvidenceTo(EvidenceItemEntity,AuthenticatedActor)」负责主链路中的“证据”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean visibleEvidenceTo(
            EvidenceItemEntity item, AuthenticatedActor actor) {
        ActorRole role = actor.role();
        if (role == ActorRole.PLATFORM_REVIEWER
                || role == ActorRole.ADMIN
                || role == ActorRole.SYSTEM) {
            return true;
        }
        if (role == ActorRole.CUSTOMER_SERVICE) {
            return "PARTIES".equals(item.getVisibility())
                    || "PLATFORM".equals(item.getVisibility());
        }
        return role.name().equals(item.getSubmittedByRole())
                && actor.actorId().equals(item.getSubmittedById())
                && item.getSubmissionStatus() == EvidenceSubmissionStatus.SUBMITTED;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.readJson(String)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.readJson(String)」：读取JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.createObjectNode」、「objectMapper.readTree」，最终返回「JsonNode」。
    // 上游调用：「EvidenceContextEnvelopeFactory.readJson(String)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」、「EvidenceContextEnvelopeFactory.visibleEvidence」、「EvidenceContextEnvelopeFactory.recentTurns」。
    // 下游影响：「EvidenceContextEnvelopeFactory.readJson(String)」向下依次触达 「objectMapper.createObjectNode」、「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.readJson(String)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.hasText(String)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.hasText(String)」：判断是否存在文本，最终返回「boolean」。
    // 上游调用：「EvidenceContextEnvelopeFactory.hasText(String)」只由「EvidenceContextEnvelopeFactory」内部流程使用，负责封装“文本”这一步校验、映射或状态转换。
    // 下游影响：「EvidenceContextEnvelopeFactory.hasText(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.hasText(String)」负责主链路中的“文本”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.isoTimestamp(OffsetDateTime)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.isoTimestamp(OffsetDateTime)」：判断是否isoTimestamp，最终返回「String」。
    // 上游调用：「EvidenceContextEnvelopeFactory.isoTimestamp(OffsetDateTime)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」、「EvidenceContextEnvelopeFactory.caseSnapshot」、「EvidenceContextEnvelopeFactory.intakeDossierSnapshot」、「EvidenceContextEnvelopeFactory.visibleEvidence」。
    // 下游影响：「EvidenceContextEnvelopeFactory.isoTimestamp(OffsetDateTime)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.isoTimestamp(OffsetDateTime)」负责主链路中的“isoTimestamp”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String isoTimestamp(java.time.OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】类型「RecentTurnsWindow」。
    // 类型职责：定义最近轮对话Window跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record RecentTurnsWindow(
            int sourceCount, boolean truncated, List<IntakeRecentTurn> turns) {}
}
