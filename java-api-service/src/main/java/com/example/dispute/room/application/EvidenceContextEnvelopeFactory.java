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
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
        this.intakeDossierRepository = intakeDossierRepository;
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
        CaseIntakeDossierEntity intakeDossier =
                intakeDossierRepository
                        .findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE)
                        .orElse(null);
        JsonNode intakeDossierJson =
                intakeDossier == null ? null : readJson(intakeDossier.getDossierJson());
        JsonNode sharedIntakeDossierJson = sharedIntakeDossierProjection(intakeDossierJson);
        RecentTurnsWindow recentTurns = recentTurns(agentSession);
        List<EvidenceContextEnvelopeV1.VisibleEvidence> visibleEvidence =
                visibleEvidence(dispute.getId(), actor);
        validateEvidenceReferences(attachmentRefs, visibleEvidence);

        return new EvidenceContextEnvelopeV1(
                EvidenceContextEnvelopeV1.SCHEMA_VERSION,
                clock.instant().toString(),
                caseSnapshot(dispute, sharedIntakeDossierJson),
                intakeDossierSnapshot(intakeDossier, sharedIntakeDossierJson),
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
                        true));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」。
    // 具体功能：「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」：构建案件快照；实际协作者为 「dispute.getId」、「dispute.getVersion」、「dispute.getCaseStatus」、「dispute.getCaseType」，最终返回「EvidenceContextEnvelopeV1.CaseSnapshot」。
    // 上游调用：「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」的上游调用点包括 「EvidenceContextEnvelopeFactory.create」。
    // 下游影响：「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」向下依次触达 「dispute.getId」、「dispute.getVersion」、「dispute.getCaseStatus」、「dispute.getCaseType」；计算结果以「EvidenceContextEnvelopeV1.CaseSnapshot」交给调用方。
    // 系统意义：「EvidenceContextEnvelopeFactory.caseSnapshot(FulfillmentCaseEntity)」负责主链路中的“案件快照”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
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
