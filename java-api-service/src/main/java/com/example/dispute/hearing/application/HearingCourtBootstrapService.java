/*
 * 所属模块：共享小法庭。
 * 文件职责：编排庭审法庭开庭装卷规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「bootstrap」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【共享小法庭 / 应用编排层】类型「HearingCourtBootstrapService」。
// 类型职责：编排庭审法庭开庭装卷规则、权限校验与事实读写；本类型显式提供 「HearingCourtBootstrapService」、「bootstrap」、「ensureHearingState」、「intakeFactMap」、「evidenceMatrix」、「ensureEvidenceBaseline」。
// 协作关系：主要由 「HearingCollaborationController.hearing」、「HearingCourtBootstrapServiceTest.alreadyBootstrappedPostHearingCasesRemainReadableWithoutOpeningANewCourtSession」、「HearingCourtBootstrapServiceTest.bootstrapCreatesAnEmptyEvidenceBaselineWhenNoEvidenceDossierExists」、「HearingCourtBootstrapServiceTest.bootstrapsTheHearingByFreezingPriorDossiersAndAppendingOpeningMessages」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class HearingCourtBootstrapService {

    static final String BOOTSTRAP_ACTOR_ID = "hearing-bootstrap";
    static final String BOOTSTRAP_NODE = "C0_COURT_BOOTSTRAP";
    static final String SNAPSHOT_RECORD_TYPE = "BOOTSTRAP_DOSSIER_SNAPSHOT";
    static final int OPENING_ROUND_NO = 1;

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final EvidenceDossierRepository evidenceDossierRepository;
    private final HearingStateRepository hearingStateRepository;
    private final HearingRecordRepository hearingRecordRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final HearingRoundService hearingRoundService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.HearingCourtBootstrapService(FulfillmentCaseRepository,CaseRoomRepository,CaseIntakeDossierRepository,EvidenceDossierRepository,HearingStateRepository,HearingRecordRepository,RoomMessageRepository,CaseEventService,HearingRoundService,ObjectMapper,Clock)」。
    // 具体功能：「HearingCourtBootstrapService.HearingCourtBootstrapService(FulfillmentCaseRepository,CaseRoomRepository,CaseIntakeDossierRepository,EvidenceDossierRepository,HearingStateRepository,HearingRecordRepository,RoomMessageRepository,CaseEventService,HearingRoundService,ObjectMapper,Clock)」：通过构造器接收 「caseRepository」(FulfillmentCaseRepository)、「roomRepository」(CaseRoomRepository)、「intakeDossierRepository」(CaseIntakeDossierRepository)、「evidenceDossierRepository」(EvidenceDossierRepository)、「hearingStateRepository」(HearingStateRepository)、「hearingRecordRepository」(HearingRecordRepository)、「messageRepository」(RoomMessageRepository)、「eventService」(CaseEventService)、「hearingRoundService」(HearingRoundService)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「HearingCourtBootstrapService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「HearingCourtBootstrapService.HearingCourtBootstrapService(FulfillmentCaseRepository,CaseRoomRepository,CaseIntakeDossierRepository,EvidenceDossierRepository,HearingStateRepository,HearingRecordRepository,RoomMessageRepository,CaseEventService,HearingRoundService,ObjectMapper,Clock)」的上游创建点包括 「HearingCourtBootstrapServiceTest.setUp」。
    // 下游影响：「HearingCourtBootstrapService.HearingCourtBootstrapService(FulfillmentCaseRepository,CaseRoomRepository,CaseIntakeDossierRepository,EvidenceDossierRepository,HearingStateRepository,HearingRecordRepository,RoomMessageRepository,CaseEventService,HearingRoundService,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingCourtBootstrapService.HearingCourtBootstrapService(FulfillmentCaseRepository,CaseRoomRepository,CaseIntakeDossierRepository,EvidenceDossierRepository,HearingStateRepository,HearingRecordRepository,RoomMessageRepository,CaseEventService,HearingRoundService,ObjectMapper,Clock)」负责主链路中的“庭审法庭开庭装卷服务”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingCourtBootstrapService(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            EvidenceDossierRepository evidenceDossierRepository,
            HearingStateRepository hearingStateRepository,
            HearingRecordRepository hearingRecordRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            HearingRoundService hearingRoundService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.evidenceDossierRepository = evidenceDossierRepository;
        this.hearingStateRepository = hearingStateRepository;
        this.hearingRecordRepository = hearingRecordRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.hearingRoundService = hearingRoundService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.bootstrap(String,AuthenticatedActor,String)」。
    // 具体功能：「HearingCourtBootstrapService.bootstrap(String,AuthenticatedActor,String)」：初始化庭审法庭开庭装卷：先由 Spring 事务代理统一提交数据库变化，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」、「hearingStateRepository.findByCaseId」、「roomRepository.findByCaseIdAndRoomType」、「intakeDossierRepository.findByCaseIdAndRoomType」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「JUDGE」、「presiding-judge」、「judge-opening」、「INTAKE_OFFICER」，最终返回「void」。
    // 上游调用：「HearingCourtBootstrapService.bootstrap(String,AuthenticatedActor,String)」的上游调用点包括 「HearingCollaborationController.hearing」、「HearingCourtBootstrapServiceTest.bootstrapsTheHearingByFreezingPriorDossiersAndAppendingOpeningMessages」、「HearingCourtBootstrapServiceTest.repeatedBootstrapDoesNotDuplicateSnapshotOrOpeningMessages」、「HearingCourtBootstrapServiceTest.bootstrapCreatesAnEmptyEvidenceBaselineWhenNoEvidenceDossierExists」。
    // 下游影响：「HearingCourtBootstrapService.bootstrap(String,AuthenticatedActor,String)」向下依次触达 「caseRepository.findByIdForUpdate」、「hearingStateRepository.findByCaseId」、「roomRepository.findByCaseIdAndRoomType」、「intakeDossierRepository.findByCaseIdAndRoomType」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「HearingCourtBootstrapService.bootstrap(String,AuthenticatedActor,String)」定义原子提交边界；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public void bootstrap(String caseId, AuthenticatedActor actor, String traceId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        assertActorCanAccess(dispute, actor);
        if (!canOpenOrRefreshBootstrap(dispute)) {
            if (canReadExistingBootstrappedCourt(dispute)
                    && hearingStateRepository.findByCaseId(caseId).isPresent()) {
                return;
            }
            throw new IllegalStateException(
                    "hearing bootstrap is unavailable from " + dispute.getCaseStatus());
        }
        CaseRoomEntity hearingRoom =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.HEARING)
                        .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
        HearingStateEntity hearingState = ensureHearingState(dispute);
        Optional<CaseIntakeDossierEntity> intake =
                intakeDossierRepository.findByCaseIdAndRoomType(caseId, RoomType.INTAKE);
        EvidenceDossierEntity evidence = ensureEvidenceBaseline(caseId);
        int evidenceDossierVersion = evidence.getDossierVersion();

        ObjectNode intakeFactMap = intakeFactMap(dispute, intake);
        ObjectNode evidenceMatrix = evidenceMatrix(Optional.of(evidence));
        String judgeOpening = judgeOpeningText(dispute);
        String intakeAnnouncement = intakeAnnouncementText(intakeFactMap);
        String evidenceAnnouncement = evidenceAnnouncementText(evidenceMatrix);
        String firstRoundQuestion = firstRoundQuestionText(intakeFactMap, evidenceMatrix);
        ObjectNode snapshot =
                courtroomContext(
                        dispute,
                        hearingState,
                        evidenceDossierVersion,
                        intakeFactMap,
                        evidenceMatrix,
                        judgeOpening,
                        intakeAnnouncement,
                        evidenceAnnouncement,
                        firstRoundQuestion);

        recordSnapshotIfAbsent(hearingState, snapshot);
        hearingRoundService.ensureInitialRoundOpen(
                caseId, evidenceDossierVersion, BOOTSTRAP_ACTOR_ID);
        appendAgentMessageIfAbsent(
                dispute,
                hearingRoom,
                "JUDGE",
                "presiding-judge",
                judgeOpening,
                bootstrapMessageKey(caseId, "judge-opening"),
                traceId);
        appendAgentMessageIfAbsent(
                dispute,
                hearingRoom,
                "INTAKE_OFFICER",
                "intake-officer",
                intakeAnnouncement,
                bootstrapMessageKey(caseId, "intake-dossier-reading"),
                traceId);
        appendAgentMessageIfAbsent(
                dispute,
                hearingRoom,
                "EVIDENCE_CLERK",
                "evidence-clerk",
                evidenceAnnouncement,
                bootstrapMessageKey(caseId, "evidence-dossier-reading"),
                traceId);
        appendAgentMessageIfAbsent(
                dispute,
                hearingRoom,
                "JUDGE",
                "presiding-judge",
                firstRoundQuestion,
                judgeRoundOpeningKey(caseId),
                traceId);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.ensureHearingState(FulfillmentCaseEntity)」。
    // 具体功能：「HearingCourtBootstrapService.ensureHearingState(FulfillmentCaseEntity)」：确保庭审状态：先把新状态写入 PostgreSQL 事实表；实际协作者为 「hearingStateRepository.findByCaseId」、「caseRepository.save」、「hearingStateRepository.save」、「HearingStateEntity.start」；处理的关键状态/协议值包括 「hearing-window-」、「HEARING_」，最终返回「HearingStateEntity」。
    // 上游调用：「HearingCourtBootstrapService.ensureHearingState(FulfillmentCaseEntity)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.ensureHearingState(FulfillmentCaseEntity)」向下依次触达 「hearingStateRepository.findByCaseId」、「caseRepository.save」、「hearingStateRepository.save」、「HearingStateEntity.start」；计算结果以「HearingStateEntity」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.ensureHearingState(FulfillmentCaseEntity)」负责主链路中的“庭审状态”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private HearingStateEntity ensureHearingState(FulfillmentCaseEntity dispute) {
        return hearingStateRepository
                .findByCaseId(dispute.getId())
                .orElseGet(
                        () -> {
                            String workflowId =
                                    dispute.getCurrentWorkflowId() == null
                                            || dispute.getCurrentWorkflowId().isBlank()
                                            ? "hearing-window-" + dispute.getId()
                                            : dispute.getCurrentWorkflowId();
                            if (dispute.getCurrentWorkflowId() == null
                                    || dispute.getCurrentWorkflowId().isBlank()) {
                                dispute.attachHearingWorkflow(workflowId, BOOTSTRAP_ACTOR_ID);
                                caseRepository.save(dispute);
                            }
                            return hearingStateRepository.save(
                                    HearingStateEntity.start(
                                            "HEARING_" + compactUuid(),
                                            dispute.getId(),
                                            workflowId,
                                            BOOTSTRAP_ACTOR_ID));
                        });
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.intakeFactMap(FulfillmentCaseEntity,Optional)」。
    // 具体功能：「HearingCourtBootstrapService.intakeFactMap(FulfillmentCaseEntity,Optional)」：构建接待事实映射；实际协作者为 「objectMapper.createObjectNode」、「intake.isPresent」、「dispute.getDescription」、「dispute.getInitiatorRole」；处理的关键状态/协议值包括 「source」、「case_intake_dossier」、「case_table_fallback」、「dossier_version」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.intakeFactMap(FulfillmentCaseEntity,Optional)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.intakeFactMap(FulfillmentCaseEntity,Optional)」向下依次触达 「objectMapper.createObjectNode」、「intake.isPresent」、「dispute.getDescription」、「dispute.getInitiatorRole」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.intakeFactMap(FulfillmentCaseEntity,Optional)」负责主链路中的“接待事实映射”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode intakeFactMap(
            FulfillmentCaseEntity dispute, Optional<CaseIntakeDossierEntity> intake) {
        JsonNode source =
                intake.map(CaseIntakeDossierEntity::getDossierJson)
                        .map(this::readJsonObject)
                        .orElseGet(objectMapper::createObjectNode);
        ObjectNode map = objectMapper.createObjectNode();
        map.put("source", intake.isPresent() ? "case_intake_dossier" : "case_table_fallback");
        map.put("dossier_version", intake.map(CaseIntakeDossierEntity::getDossierVersion).orElse(0));
        map.put("ready_for_next_step", intake.map(CaseIntakeDossierEntity::isReadyForNextStep).orElse(false));
        map.put(
                "admission_recommendation",
                intake.map(CaseIntakeDossierEntity::getAdmissionRecommendation)
                        .orElse("UNKNOWN"));
        map.put("case_story", caseStoryText(source, dispute.getDescription()));
        map.put("initiator_role", dispute.getInitiatorRole().name());
        map.put("respondent_role", respondentRole(dispute.getInitiatorRole()).name());
        map.set("claim", objectOrDefault(source.path("claim"), defaultClaim(source)));
        map.set(
                "claim_resolution",
                objectOrDefault(source.path("claim_resolution"), defaultClaimResolution(dispute, source)));
        map.set(
                "respondent_attitude",
                objectOrDefault(
                        source.path("respondent_attitude"),
                        defaultRespondentAttitude(dispute)));
        map.set(
                "dispute_core_state",
                objectOrDefault(
                        source.path("dispute_core_state"),
                        defaultDisputeCoreState(dispute)));
        map.set("timeline", arrayOrEmpty(source.path("timeline")));
        map.set("known_facts", arrayOrDefault(source.path("known_facts"), defaultKnownFacts(dispute)));
        map.set(
                "disputed_facts",
                arrayOrDefault(source.path("disputed_facts"), defaultDisputedFacts(dispute)));
        map.set("missing_information", arrayOrEmpty(source.path("missing_information")));
        map.set(
                "legal_or_platform_policy_hooks",
                arrayOrDefault(
                        source.path("legal_or_platform_policy_hooks"),
                        defaultPolicyHooks(dispute)));
        map.put("risk_level", dispute.getRiskLevel().name());
        map.put(
                "quality_score",
                intake.map(CaseIntakeDossierEntity::getQualityScore)
                        .orElse(source.path("intake_quality").path("score").asInt(0)));
        map.put(
                "handoff_notes",
                firstText(
                        source,
                        "庭审应围绕争议事实、证据强度和双方诉求进行结构化核验。",
                        "handoff_notes",
                        "next_step_hint"));
        return map;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.evidenceMatrix(Optional)」。
    // 具体功能：「HearingCourtBootstrapService.evidenceMatrix(Optional)」：构建证据矩阵：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「objectMapper.createObjectNode」、「evidence.isPresent」、「summary.deepCopy」、「dossier.putObject」；处理的关键状态/协议值包括 「source」、「evidence_dossier」、「evidence_dossier_missing」、「dossier_id」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.evidenceMatrix(Optional)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.evidenceMatrix(Optional)」向下依次触达 「objectMapper.createObjectNode」、「evidence.isPresent」、「summary.deepCopy」、「dossier.putObject」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.evidenceMatrix(Optional)」负责主链路中的“证据矩阵”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode evidenceMatrix(Optional<EvidenceDossierEntity> evidence) {
        JsonNode summary =
                evidence.map(EvidenceDossierEntity::getSummaryJson)
                        .map(this::readJsonObject)
                        .orElseGet(objectMapper::createObjectNode);
        JsonNode timeline =
                evidence.map(EvidenceDossierEntity::getTimelineJson)
                        .map(this::readJsonArray)
                        .orElseGet(objectMapper::createArrayNode);
        JsonNode matrix =
                evidence.map(EvidenceDossierEntity::getMatrixSummaryJson)
                        .map(this::readJson)
                        .orElseGet(objectMapper::createArrayNode);
        ObjectNode dossier = objectMapper.createObjectNode();
        dossier.put("source", evidence.isPresent() ? "evidence_dossier" : "evidence_dossier_missing");
        dossier.put("dossier_id", evidence.map(EvidenceDossierEntity::getId).orElse(""));
        dossier.put("dossier_version", evidence.map(EvidenceDossierEntity::getDossierVersion).orElse(0));
        dossier.put("dossier_status", evidence.map(EvidenceDossierEntity::getDossierStatus).orElse("MISSING"));
        dossier.set("summary", summary.deepCopy());
        dossier.set("evidence_items", arrayOrEmpty(summary.path("evidence_items")));
        dossier.set("timeline", arrayOrEmpty(timeline));
        dossier.set(
                "fact_evidence_matrix",
                matrix.path("fact_evidence_matrix").isArray()
                        ? matrix.path("fact_evidence_matrix").deepCopy()
                        : arrayOrEmpty(matrix));
        dossier.set(
                "party_evidence_summary",
                objectOrDefault(
                        summary.path("party_evidence_summary"), defaultPartyEvidenceSummary()));
        dossier.set("verified_facts", arrayOrEmpty(summary.path("verified_facts")));
        dossier.set("contested_facts", arrayOrEmpty(summary.path("contested_facts")));
        dossier.set("evidence_gaps", arrayOrEmpty(summary.path("evidence_gaps")));
        dossier.set("authenticity_flags", arrayOrEmpty(summary.path("authenticity_flags")));
        dossier.set(
                "human_review_tasks",
                arrayOrEmpty(matrix.path("human_review_tasks")));
        dossier.set(
                "evidence_clerk_a2a_handoffs",
                arrayOrEmpty(matrix.path("internal_handoffs")));
        dossier.put(
                "overall_confidence_score",
                summary.path("overall_confidence_score").asInt(
                        summary.path("confidence_score").asInt(0)));
        dossier.put(
                "handoff_notes",
                firstText(
                        summary,
                        "证据室尚未形成完整证明矩阵，庭审应继续核验证据真实性、关联性和完整性。",
                        "handoff_notes",
                        "summary"));
        ObjectNode rawProjection = dossier.putObject("raw_projection");
        rawProjection.set("summary_json", summary.deepCopy());
        rawProjection.set("timeline_json", timeline.deepCopy());
        rawProjection.set("matrix_summary_json", matrix.deepCopy());
        return dossier;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.ensureEvidenceBaseline(String)」。
    // 具体功能：「HearingCourtBootstrapService.ensureEvidenceBaseline(String)」：确保证据Baseline：先把新状态写入 PostgreSQL 事实表；实际协作者为 「evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「evidenceDossierRepository.save」、「emptyEvidenceBaseline」，最终返回「EvidenceDossierEntity」。
    // 上游调用：「HearingCourtBootstrapService.ensureEvidenceBaseline(String)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.ensureEvidenceBaseline(String)」向下依次触达 「evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「evidenceDossierRepository.save」、「emptyEvidenceBaseline」；计算结果以「EvidenceDossierEntity」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.ensureEvidenceBaseline(String)」负责主链路中的“证据Baseline”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private EvidenceDossierEntity ensureEvidenceBaseline(String caseId) {
        return evidenceDossierRepository
                .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                .orElseGet(() -> evidenceDossierRepository.save(emptyEvidenceBaseline(caseId)));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.emptyEvidenceBaseline(String)」。
    // 具体功能：「HearingCourtBootstrapService.emptyEvidenceBaseline(String)」：构建为空证据Baseline：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「EvidenceDossierEntity.frozen」、「objectMapper.createObjectNode」、「summary.putArray」、「matrixSummary.putArray」；处理的关键状态/协议值包括 「evidence_count」、「evidence_items」、「verification_statuses」、「party_evidence_summary」，最终返回「EvidenceDossierEntity」。
    // 上游调用：「HearingCourtBootstrapService.emptyEvidenceBaseline(String)」的上游调用点包括 「HearingCourtBootstrapService.ensureEvidenceBaseline」。
    // 下游影响：「HearingCourtBootstrapService.emptyEvidenceBaseline(String)」向下依次触达 「EvidenceDossierEntity.frozen」、「objectMapper.createObjectNode」、「summary.putArray」、「matrixSummary.putArray」；计算结果以「EvidenceDossierEntity」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.emptyEvidenceBaseline(String)」负责主链路中的“为空证据Baseline”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private EvidenceDossierEntity emptyEvidenceBaseline(String caseId) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("evidence_count", 0);
        summary.putArray("evidence_items");
        summary.putArray("verification_statuses");
        summary.set("party_evidence_summary", defaultPartyEvidenceSummary());
        summary.putArray("verified_facts");
        summary.putArray("contested_facts");
        ArrayNode gaps = summary.putArray("evidence_gaps");
        gaps.add("USER 尚未形成有效证据材料");
        gaps.add("MERCHANT 尚未形成有效证据材料");
        summary.putArray("authenticity_flags");
        summary.put("overall_confidence_score", 0);
        summary.put(
                "handoff_notes",
                "证据室尚未收到正式提交的有效证据，庭审应提醒双方围绕争议事实进行说明。");
        summary.put("frozen", true);
        summary.put("baseline_empty", true);

        ObjectNode matrixSummary = objectMapper.createObjectNode();
        matrixSummary.putArray("fact_evidence_matrix");
        matrixSummary.putArray("unmapped_evidence");
        matrixSummary.set("evidence_gaps", gaps.deepCopy());
        matrixSummary.put("handoff_notes", summary.path("handoff_notes").asText());
        matrixSummary.put("baseline_empty", true);

        return EvidenceDossierEntity.frozen(
                "EVIDENCE_DOSSIER_" + compactUuid(),
                caseId,
                1,
                BOOTSTRAP_ACTOR_ID,
                json(summary),
                "[]",
                json(matrixSummary));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.courtroomContext(FulfillmentCaseEntity,HearingStateEntity,int,ObjectNode,ObjectNode,String,String,String,String)」。
    // 具体功能：「HearingCourtBootstrapService.courtroomContext(FulfillmentCaseEntity,HearingStateEntity,int,ObjectNode,ObjectNode,String,String,String,String)」：构建法庭上下文上下文；实际协作者为 「objectMapper.createObjectNode」、「dispute.getId」、「hearingState.getWorkflowId」、「context.putObject」；处理的关键状态/协议值包括 「schema_version」、「hearing_bootstrap_dossier.v1」、「case_id」、「workflow_id」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.courtroomContext(FulfillmentCaseEntity,HearingStateEntity,int,ObjectNode,ObjectNode,String,String,String,String)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.courtroomContext(FulfillmentCaseEntity,HearingStateEntity,int,ObjectNode,ObjectNode,String,String,String,String)」向下依次触达 「objectMapper.createObjectNode」、「dispute.getId」、「hearingState.getWorkflowId」、「context.putObject」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.courtroomContext(FulfillmentCaseEntity,HearingStateEntity,int,ObjectNode,ObjectNode,String,String,String,String)」负责主链路中的“法庭上下文上下文”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode courtroomContext(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            int evidenceDossierVersion,
            ObjectNode intakeFactMap,
            ObjectNode evidenceMatrix,
            String judgeOpening,
            String intakeAnnouncement,
            String evidenceAnnouncement,
            String firstRoundQuestion) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schema_version", "hearing_bootstrap_dossier.v1");
        context.put("case_id", dispute.getId());
        context.put("workflow_id", hearingState.getWorkflowId());
        context.put("round_no", OPENING_ROUND_NO);
        context.put("round_stage", "FACT_STATEMENT");
        context.put("bootstrapped_at", Instant.now(clock).toString());
        context.put("evidence_dossier_version", evidenceDossierVersion);
        ObjectNode sourceVersions = context.putObject("source_versions");
        sourceVersions.put("case_version", dispute.getVersion());
        sourceVersions.put("intake_dossier_version", intakeFactMap.path("dossier_version").asInt(0));
        sourceVersions.put("evidence_dossier_version", evidenceDossierVersion);
        ObjectNode identity = context.putObject("case_identity");
        identity.put("order_id", nullToEmpty(dispute.getOrderId()));
        identity.put("after_sale_id", nullToEmpty(dispute.getAfterSaleId()));
        identity.put("logistics_id", nullToEmpty(dispute.getLogisticsId()));
        identity.put("dispute_type", nullToEmpty(dispute.getDisputeType()));
        identity.put("risk_level", dispute.getRiskLevel().name());
        context.set("intake_dossier", intakeFactMap);
        context.set("evidence_dossier", evidenceMatrix);
        ArrayNode warnings = context.putArray("warnings");
        if (containsUnmappedRelation(evidenceMatrix.path("fact_evidence_matrix"))) {
            warnings.add("matrix_relation_unmapped");
        }
        if (evidenceMatrix.path("fact_evidence_matrix").isEmpty()) {
            warnings.add("evidence_matrix_empty");
        }
        ArrayNode messages = context.putArray("courtroom_opening_messages");
        messages.add(openingMessage("JUDGE", judgeOpening));
        messages.add(openingMessage("INTAKE_OFFICER", intakeAnnouncement));
        messages.add(openingMessage("EVIDENCE_CLERK", evidenceAnnouncement));
        messages.add(openingMessage("JUDGE", firstRoundQuestion));
        context.putArray("current_round_party_messages");
        return context;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.openingMessage(String,String)」。
    // 具体功能：「HearingCourtBootstrapService.openingMessage(String,String)」：开放开场消息消息；实际协作者为 「objectMapper.createObjectNode」；处理的关键状态/协议值包括 「sender_role」、「content」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.openingMessage(String,String)」的上游调用点包括 「HearingCourtBootstrapService.courtroomContext」。
    // 下游影响：「HearingCourtBootstrapService.openingMessage(String,String)」向下依次触达 「objectMapper.createObjectNode」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.openingMessage(String,String)」负责主链路中的“开场消息消息”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode openingMessage(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("sender_role", role);
        message.put("content", content);
        return message;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.recordSnapshotIfAbsent(HearingStateEntity,ObjectNode)」。
    // 具体功能：「HearingCourtBootstrapService.recordSnapshotIfAbsent(HearingStateEntity,ObjectNode)」：记录快照IfAbsent：先把新状态写入 PostgreSQL 事实表；实际协作者为 「existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType」、「hearingRecordRepository.save」、「HearingRecordEntity.record」、「hearingState.getWorkflowId」；处理的关键状态/协议值包括 「HREC_」、「case_id」、「source」、「prior_room_dossiers」，最终返回「void」。
    // 上游调用：「HearingCourtBootstrapService.recordSnapshotIfAbsent(HearingStateEntity,ObjectNode)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.recordSnapshotIfAbsent(HearingStateEntity,ObjectNode)」向下依次触达 「existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType」、「hearingRecordRepository.save」、「HearingRecordEntity.record」、「hearingState.getWorkflowId」。
    // 系统意义：「HearingCourtBootstrapService.recordSnapshotIfAbsent(HearingStateEntity,ObjectNode)」负责主链路中的“快照IfAbsent”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void recordSnapshotIfAbsent(HearingStateEntity hearingState, ObjectNode snapshot) {
        if (hearingRecordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
                hearingState.getWorkflowId(),
                BOOTSTRAP_NODE,
                OPENING_ROUND_NO,
                SNAPSHOT_RECORD_TYPE)) {
            return;
        }
        hearingRecordRepository.save(
                HearingRecordEntity.record(
                        "HREC_" + compactUuid(),
                        snapshot.path("case_id").asText(),
                        hearingState.getId(),
                        hearingState.getWorkflowId(),
                        BOOTSTRAP_NODE,
                        OPENING_ROUND_NO,
                        SNAPSHOT_RECORD_TYPE,
                        objectMapper.valueToTree(Map.of("source", "prior_room_dossiers")).toString(),
                        snapshot.toString(),
                        objectMapper
                                .valueToTree(
                                        Map.of(
                                                "purpose",
                                                "freeze_intake_and_evidence_for_hearing"))
                                .toString(),
                        "hearing-bootstrap-v1",
                        "java-deterministic-bootstrap",
                        null,
                        null,
                        BOOTSTRAP_ACTOR_ID));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.appendAgentMessageIfAbsent(FulfillmentCaseEntity,CaseRoomEntity,String,String,String,String,String)」。
    // 具体功能：「HearingCourtBootstrapService.appendAgentMessageIfAbsent(FulfillmentCaseEntity,CaseRoomEntity,String,String,String,String,String)」：追加Agent消息IfAbsent：先把新状态写入 PostgreSQL 事实表；实际协作者为 「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findMaxSequenceByRoomId」、「messageRepository.save」、「eventService.recordRoomMessage」；处理的关键状态/协议值包括 「MESSAGE_」、「[]」，最终返回「void」。
    // 上游调用：「HearingCourtBootstrapService.appendAgentMessageIfAbsent(FulfillmentCaseEntity,CaseRoomEntity,String,String,String,String,String)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.appendAgentMessageIfAbsent(FulfillmentCaseEntity,CaseRoomEntity,String,String,String,String,String)」向下依次触达 「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findMaxSequenceByRoomId」、「messageRepository.save」、「eventService.recordRoomMessage」。
    // 系统意义：「HearingCourtBootstrapService.appendAgentMessageIfAbsent(FulfillmentCaseEntity,CaseRoomEntity,String,String,String,String,String)」负责主链路中的“Agent消息IfAbsent”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private void appendAgentMessageIfAbsent(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            String senderRole,
            String senderId,
            String messageText,
            String idempotencyKey,
            String traceId) {
        if (messageRepository
                .findByCaseIdAndIdempotencyKey(dispute.getId(), idempotencyKey)
                .isPresent()) {
            return;
        }
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity saved =
                messageRepository.save(
                        RoomMessageEntity.create(
                                "MESSAGE_" + compactUuid(),
                                dispute.getId(),
                                room.getId(),
                                sequence,
                                MessageSenderType.AGENT,
                                senderRole,
                                senderId,
                                sharedCourtAudienceJson(),
                                "[]",
                                MessageType.AGENT_MESSAGE,
                                messageText,
                                "[]",
                                idempotencyKey,
                                OPENING_ROUND_NO,
                                Instant.now(clock),
                                traceId));
        eventService.recordRoomMessage(
                dispute.getId(),
                room.getId(),
                saved.getId(),
                saved.getMessageText(),
                saved.getAudienceJson(),
                saved.getAudienceActorIdsJson(),
                senderId);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.judgeOpeningText(FulfillmentCaseEntity)」。
    // 具体功能：「HearingCourtBootstrapService.judgeOpeningText(FulfillmentCaseEntity)」：构建法官开场消息文本；实际协作者为 「dispute.getTitle」；处理的关键状态/协议值包括 「本案当前争议为：」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.judgeOpeningText(FulfillmentCaseEntity)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.judgeOpeningText(FulfillmentCaseEntity)」向下依次触达 「dispute.getTitle」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.judgeOpeningText(FulfillmentCaseEntity)」负责主链路中的“法官开场消息文本”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String judgeOpeningText(FulfillmentCaseEntity dispute) {
        return "现在开庭。小法庭将基于接待室案情卷宗、证据室证据卷宗和双方庭审陈述进行三轮结构化审理。"
                + "本案当前争议为："
                + dispute.getTitle()
                + "。AI 法官输出为裁决方案草案，最终结果以后续确认为准。";
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.intakeAnnouncementText(ObjectNode)」。
    // 具体功能：「HearingCourtBootstrapService.intakeAnnouncementText(ObjectNode)」：构建接待Announcement文本；实际协作者为 「firstArrayFact」、「intakeFactMap.path("case_story").asText」、「intakeFactMap.path("handoff_notes").asText」；处理的关键状态/协议值包括 「案情接待官宣读案情卷宗：」、「case_story」、「disputed_facts」、「。庭审交接备注：」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.intakeAnnouncementText(ObjectNode)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.intakeAnnouncementText(ObjectNode)」向下依次触达 「firstArrayFact」、「intakeFactMap.path("case_story").asText」、「intakeFactMap.path("handoff_notes").asText」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.intakeAnnouncementText(ObjectNode)」负责主链路中的“接待Announcement文本”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String intakeAnnouncementText(ObjectNode intakeFactMap) {
        return "案情接待官宣读案情卷宗："
                + intakeFactMap.path("case_story").asText()
                + " 主要争议事实为："
                + firstArrayFact(intakeFactMap.path("disputed_facts"))
                + "。庭审交接备注："
                + intakeFactMap.path("handoff_notes").asText();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.evidenceAnnouncementText(ObjectNode)」。
    // 具体功能：「HearingCourtBootstrapService.evidenceAnnouncementText(ObjectNode)」：从完整证据卷宗派生稳定的公开宣读摘要，只展示数量、置信度、首要结论和一项待补强内容，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.evidenceAnnouncementText(ObjectNode)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.evidenceAnnouncementText(ObjectNode)」只压缩用户可见文案，不修改 evidence_dossier、人工复核任务或 A2A 交接数据；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.evidenceAnnouncementText(ObjectNode)」负责主链路中的“证据Announcement文本”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String evidenceAnnouncementText(ObjectNode evidenceMatrix) {
        int evidenceCount = evidenceMatrix.path("evidence_items").size();
        int confidence = evidenceMatrix.path("overall_confidence_score").asInt(0);
        String finding = firstPublicEvidenceFinding(evidenceMatrix);
        String gap = firstPublicArrayText(evidenceMatrix.path("evidence_gaps"));

        StringBuilder announcement =
                new StringBuilder("证据书记官宣读证据卷宗：已完成证据装卷，共 ")
                        .append(evidenceCount)
                        .append(" 份，总体置信度 ")
                        .append(Math.max(0, Math.min(confidence, 100)))
                        .append("/100。核验结论：")
                        .append(
                                finding.isBlank()
                                        ? "当前材料尚未形成可采信的事实结论"
                                        : finding);
        if (!gap.isBlank() && !publicTextOverlaps(finding, gap)) {
            announcement.append("。待补强：").append(gap);
        }
        return announcement.append("。").toString();
    }

    /**
     * 只为庭审聊天卡片生成一条可宣读结论。正式证据矩阵、人工复核任务和 A2A 交接仍保留在
     * {@code evidence_dossier}，这里不得用展示摘要反向替换结构化卷宗。
     */
    private static String firstPublicEvidenceFinding(ObjectNode evidenceMatrix) {
        for (String field : List.of("verified_facts", "contested_facts")) {
            String value = firstPublicArrayText(evidenceMatrix.path(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        JsonNode matrix = evidenceMatrix.path("fact_evidence_matrix");
        if (!matrix.isArray() || matrix.isEmpty()) {
            return "";
        }
        JsonNode first = matrix.get(0);
        String fact = publicNodeText(first);
        if (!fact.isBlank()) {
            return fact;
        }
        String relation = localizedRelationType(first.path("relation_type").asText(""));
        String verification =
                localizedVerificationStatus(first.path("verification_status").asText(""));
        return compactPublicText(
                "首份证据"
                        + (relation.isBlank() ? "已入卷" : relation)
                        + (verification.isBlank() ? "" : "，当前" + verification));
    }

    private static String firstPublicArrayText(JsonNode array) {
        if (!array.isArray()) {
            return "";
        }
        for (JsonNode item : array) {
            String value = publicNodeText(item);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String publicNodeText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return compactPublicText(node.asText());
        }
        for (String field :
                List.of(
                        "fact",
                        "event",
                        "claimed_fact",
                        "summary",
                        "description",
                        "title",
                        "reason",
                        "gap")) {
            String value = compactPublicText(node.path(field).asText(""));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String compactPublicText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized =
                raw.trim()
                        .replaceAll("(?i)(^|[\\s；;。,:：])s(?=[\\p{IsHan}])", "$1")
                        .replaceAll("(?i)\\bUSER\\b", "用户")
                        .replaceAll("(?i)\\bMERCHANT\\b", "商家")
                        .replaceAll("^[\\-—•·*\\d.、\\s]+", "")
                        .replaceAll("\\s+", " ")
                        .replaceAll("[。；;，,\\s]+$", "")
                        .trim();
        int maxCodePoints = 84;
        if (normalized.codePointCount(0, normalized.length()) <= maxCodePoints) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, end).replaceAll("[。；;，,\\s]+$", "") + "…";
    }

    private static boolean publicTextOverlaps(String left, String right) {
        String normalizedLeft =
                compactPublicText(left).replaceAll("[\\p{Punct}\\s，。；：、]", "");
        String normalizedRight =
                compactPublicText(right).replaceAll("[\\p{Punct}\\s，。；：、]", "");
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) {
            return false;
        }
        return normalizedLeft.contains(normalizedRight)
                || normalizedRight.contains(normalizedLeft);
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.firstRoundQuestionText(ObjectNode,ObjectNode)」。
    // 具体功能：「HearingCourtBootstrapService.firstRoundQuestionText(ObjectNode,ObjectNode)」：构建首版轮次Question文本；实际协作者为 「firstArrayFact」、「evidenceMatrix.path("handoff_notes").asText」；处理的关键状态/协议值包括 「请用户围绕“」、「disputed_facts」、「”补充实际经过、时间地点和希望平台核验的事实；」、「双方陈述应尽量对应证据书记官指出的证明缺口：」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.firstRoundQuestionText(ObjectNode,ObjectNode)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.firstRoundQuestionText(ObjectNode,ObjectNode)」向下依次触达 「firstArrayFact」、「evidenceMatrix.path("handoff_notes").asText」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.firstRoundQuestionText(ObjectNode,ObjectNode)」负责主链路中的“首版轮次Question文本”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String firstRoundQuestionText(
            ObjectNode intakeFactMap, ObjectNode evidenceMatrix) {
        return "根据案情事实地图和证据证明矩阵，现在进入第 1 轮事实陈述。"
                + "请用户围绕“"
                + firstArrayFact(intakeFactMap.path("disputed_facts"))
                + "”补充实际经过、时间地点和希望平台核验的事实；"
                + "请商家围绕履约、发货、物流交接和签收记录说明与用户主张不一致的部分。"
                + "双方陈述应尽量对应证据书记官指出的证明缺口："
                + evidenceMatrix.path("handoff_notes").asText();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.firstArrayFact(JsonNode)」。
    // 具体功能：「HearingCourtBootstrapService.firstArrayFact(JsonNode)」：构建首版数组事实；实际协作者为 「array.isArray」、「first.isTextual」、「first.asText」、「first.has」；处理的关键状态/协议值包括 「当前争议事实仍需双方在庭审中进一步说明」、「fact」、「event」、「claimed_fact」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.firstArrayFact(JsonNode)」的上游调用点包括 「HearingCourtBootstrapService.intakeAnnouncementText」、「HearingCourtBootstrapService.firstRoundQuestionText」。
    // 下游影响：「HearingCourtBootstrapService.firstArrayFact(JsonNode)」向下依次触达 「array.isArray」、「first.isTextual」、「first.asText」、「first.has」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.firstArrayFact(JsonNode)」负责主链路中的“首版数组事实”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String firstArrayFact(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return "当前争议事实仍需双方在庭审中进一步说明";
        }
        JsonNode first = array.get(0);
        if (first.isTextual()) {
            return first.asText();
        }
        for (String field : List.of("fact", "event", "claimed_fact", "summary", "description", "title")) {
            String value = first.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        String userPosition = first.path("user_position").asText("");
        String merchantPosition = first.path("merchant_position").asText("");
        if (!userPosition.isBlank() || !merchantPosition.isBlank()) {
            return "用户主张："
                    + textOrDefault(userPosition, "待补充")
                    + "；商家主张："
                    + textOrDefault(merchantPosition, "待补充");
        }
        if (first.has("evidence_id")
                || first.has("relation_type")
                || first.has("verification_status")
                || first.has("evidence_strength")) {
            return localizedEvidenceMatrixRow(first);
        }
        return "当前条目缺少可直接宣读的事实摘要，庭审中需进一步说明材料与争议事实的关系";
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.localizedEvidenceMatrixRow(JsonNode)」。
    // 具体功能：「HearingCourtBootstrapService.localizedEvidenceMatrixRow(JsonNode)」：构建localized证据矩阵Row；实际协作者为 「builder.append」、「localizedRelationType」、「localizedVerificationStatus」、「localizedEvidenceStrength」；处理的关键状态/协议值包括 「relation_type」、「verification_status」、「evidence_strength」、「首份证据材料」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.localizedEvidenceMatrixRow(JsonNode)」的上游调用点包括 「HearingCourtBootstrapService.firstArrayFact」。
    // 下游影响：「HearingCourtBootstrapService.localizedEvidenceMatrixRow(JsonNode)」向下依次触达 「builder.append」、「localizedRelationType」、「localizedVerificationStatus」、「localizedEvidenceStrength」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.localizedEvidenceMatrixRow(JsonNode)」负责主链路中的“localized证据矩阵Row”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String localizedEvidenceMatrixRow(JsonNode row) {
        String relation = localizedRelationType(row.path("relation_type").asText(""));
        String verification = localizedVerificationStatus(row.path("verification_status").asText(""));
        String strength = localizedEvidenceStrength(row.path("evidence_strength").asText(""));
        StringBuilder builder = new StringBuilder("首份证据材料");
        if (!relation.isBlank()) {
            builder.append(relation);
        } else {
            builder.append("已入卷，但尚未形成明确证明方向");
        }
        if (!verification.isBlank()) {
            builder.append("，当前核验状态为").append(verification);
        }
        if (!strength.isBlank()) {
            builder.append("，证明强度为").append(strength);
        }
        builder.append("。庭审中应继续说明该材料对应的争议事实、形成时间和来源链路");
        return builder.toString();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.localizedRelationType(String)」。
    // 具体功能：「HearingCourtBootstrapService.localizedRelationType(String)」：构建localized关联类型；处理的关键状态/协议值包括 「UNMAPPED」、「UNKNOWN」、「尚未映射到具体争议事实」、「SUPPORTS」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.localizedRelationType(String)」的上游调用点包括 「HearingCourtBootstrapService.localizedEvidenceMatrixRow」。
    // 下游影响：「HearingCourtBootstrapService.localizedRelationType(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.localizedRelationType(String)」负责主链路中的“localized关联类型”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String localizedRelationType(String relationType) {
        return switch (relationType == null ? "" : relationType.toUpperCase()) {
            case "UNMAPPED", "UNKNOWN", "" -> "尚未映射到具体争议事实";
            case "SUPPORTS", "SUPPORTING", "SUPPORT" -> "支持相关争议事实";
            case "OPPOSES", "OPPOSING", "REFUTES" -> "反驳相关争议事实";
            case "PARTIAL", "PARTIALLY_SUPPORTS" -> "与相关争议事实存在部分关联";
            default -> "已关联到争议事实";
        };
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.localizedVerificationStatus(String)」。
    // 具体功能：「HearingCourtBootstrapService.localizedVerificationStatus(String)」：构建localized核验状态；处理的关键状态/协议值包括 「UNVERIFIED」、「PENDING」、「UNKNOWN」、「待核验」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.localizedVerificationStatus(String)」的上游调用点包括 「HearingCourtBootstrapService.localizedEvidenceMatrixRow」。
    // 下游影响：「HearingCourtBootstrapService.localizedVerificationStatus(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.localizedVerificationStatus(String)」负责主链路中的“localized核验状态”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String localizedVerificationStatus(String verificationStatus) {
        return switch (verificationStatus == null ? "" : verificationStatus.toUpperCase()) {
            case "UNVERIFIED", "PENDING", "UNKNOWN", "" -> "待核验";
            case "VERIFIED" -> "已核验";
            case "PARTIALLY_VERIFIED" -> "部分核验";
            case "QUESTIONABLE", "SUSPICIOUS" -> "存疑，需人工复核";
            case "REJECTED" -> "未采纳";
            default -> "待复核";
        };
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.localizedEvidenceStrength(String)」。
    // 具体功能：「HearingCourtBootstrapService.localizedEvidenceStrength(String)」：构建localized证据证明力；处理的关键状态/协议值包括 「HIGH」、「STRONG」、「较强」、「MEDIUM」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.localizedEvidenceStrength(String)」的上游调用点包括 「HearingCourtBootstrapService.localizedEvidenceMatrixRow」。
    // 下游影响：「HearingCourtBootstrapService.localizedEvidenceStrength(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.localizedEvidenceStrength(String)」负责主链路中的“localized证据证明力”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String localizedEvidenceStrength(String evidenceStrength) {
        return switch (evidenceStrength == null ? "" : evidenceStrength.toUpperCase()) {
            case "HIGH", "STRONG" -> "较强";
            case "MEDIUM" -> "中等";
            case "LOW", "WEAK" -> "较弱";
            case "NONE", "UNKNOWN", "" -> "";
            default -> "待评估";
        };
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.textOrDefault(String,String)」。
    // 具体功能：「HearingCourtBootstrapService.textOrDefault(String,String)」：构建文本或者默认，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.textOrDefault(String,String)」的上游调用点包括 「HearingCourtBootstrapService.firstArrayFact」。
    // 下游影响：「HearingCourtBootstrapService.textOrDefault(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.textOrDefault(String,String)」负责主链路中的“文本或者默认”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.defaultClaim(JsonNode)」。
    // 具体功能：「HearingCourtBootstrapService.defaultClaim(JsonNode)」：构建默认主张；实际协作者为 「objectMapper.createObjectNode」、「claim.putNull」、「firstText」；处理的关键状态/协议值包括 「requested_resolution」、「待庭审确认」、「resolution」、「amount」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.defaultClaim(JsonNode)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「HearingCourtBootstrapService.defaultClaim(JsonNode)」向下依次触达 「objectMapper.createObjectNode」、「claim.putNull」、「firstText」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.defaultClaim(JsonNode)」负责主链路中的“默认主张”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode defaultClaim(JsonNode source) {
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put(
                "requested_resolution",
                firstText(source, "待庭审确认", "requested_resolution", "resolution"));
        claim.putNull("amount");
        claim.put("non_monetary_request", "核验争议事实与证据链路");
        return claim;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.defaultClaimResolution(FulfillmentCaseEntity,JsonNode)」。
    // 具体功能：「HearingCourtBootstrapService.defaultClaimResolution(FulfillmentCaseEntity,JsonNode)」：构建默认主张Resolution；实际协作者为 「objectMapper.createObjectNode」、「dispute.getInitiatorRole」、「claim.putNull」、「dispute.getDescription」；处理的关键状态/协议值包括 「UNKNOWN」、「requested_resolution」、「resolution」、「initiator_role」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.defaultClaimResolution(FulfillmentCaseEntity,JsonNode)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「HearingCourtBootstrapService.defaultClaimResolution(FulfillmentCaseEntity,JsonNode)」向下依次触达 「objectMapper.createObjectNode」、「dispute.getInitiatorRole」、「claim.putNull」、「dispute.getDescription」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.defaultClaimResolution(FulfillmentCaseEntity,JsonNode)」负责主链路中的“默认主张Resolution”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode defaultClaimResolution(FulfillmentCaseEntity dispute, JsonNode source) {
        ObjectNode claim = objectMapper.createObjectNode();
        String initiator = dispute.getInitiatorRole().name();
        String requestedResolution =
                firstText(source, "UNKNOWN", "requested_resolution", "resolution");
        claim.put("initiator_role", initiator);
        claim.put("requested_resolution", requestedResolution);
        claim.putNull("requested_amount");
        claim.put("requested_items", "");
        claim.put("request_reason", dispute.getDescription());
        claim.put("original_statement", dispute.getDescription());
        claim.put(
                "normalized_statement",
                roleLabel(initiator) + "称" + dispute.getDescription());
        return claim;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.defaultRespondentAttitude(FulfillmentCaseEntity)」。
    // 具体功能：「HearingCourtBootstrapService.defaultRespondentAttitude(FulfillmentCaseEntity)」：构建默认被申请方态度；实际协作者为 「dispute.getInitiatorRole」、「objectMapper.createObjectNode」、「respondentRole」、「roleLabel」；处理的关键状态/协议值包括 「respondent_role」、「attitude」、「NOT_RESPONDED」、「position」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.defaultRespondentAttitude(FulfillmentCaseEntity)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「HearingCourtBootstrapService.defaultRespondentAttitude(FulfillmentCaseEntity)」向下依次触达 「dispute.getInitiatorRole」、「objectMapper.createObjectNode」、「respondentRole」、「roleLabel」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.defaultRespondentAttitude(FulfillmentCaseEntity)」负责主链路中的“默认被申请方态度”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode defaultRespondentAttitude(FulfillmentCaseEntity dispute) {
        String respondent = respondentRole(dispute.getInitiatorRole()).name();
        ObjectNode attitude = objectMapper.createObjectNode();
        attitude.put("respondent_role", respondent);
        attitude.put("attitude", "NOT_RESPONDED");
        attitude.put("position", roleLabel(respondent) + "尚未在接待室表达态度。");
        attitude.put("source", "尚未回应");
        attitude.put("confidence", 0.5);
        return attitude;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.defaultDisputeCoreState(FulfillmentCaseEntity)」。
    // 具体功能：「HearingCourtBootstrapService.defaultDisputeCoreState(FulfillmentCaseEntity)」：构建默认争议Core状态；实际协作者为 「dispute.getInitiatorRole」、「objectMapper.createObjectNode」、「objectMapper.createArrayNode」、「respondentRole」；处理的关键状态/协议值包括 「core_conflict」、「提出处理诉求，但」、「态度尚待补充。」、「conflict_type」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.defaultDisputeCoreState(FulfillmentCaseEntity)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「HearingCourtBootstrapService.defaultDisputeCoreState(FulfillmentCaseEntity)」向下依次触达 「dispute.getInitiatorRole」、「objectMapper.createObjectNode」、「objectMapper.createArrayNode」、「respondentRole」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.defaultDisputeCoreState(FulfillmentCaseEntity)」负责主链路中的“默认争议Core状态”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode defaultDisputeCoreState(FulfillmentCaseEntity dispute) {
        String initiator = dispute.getInitiatorRole().name();
        String respondent = respondentRole(dispute.getInitiatorRole()).name();
        ObjectNode state = objectMapper.createObjectNode();
        state.put(
                "core_conflict",
                roleLabel(initiator) + "提出处理诉求，但" + roleLabel(respondent) + "态度尚待补充。");
        state.put("conflict_type", "CLAIM_UNANSWERED");
        state.set("facts_in_dispute", objectMapper.createArrayNode());
        state.set("next_verification_focus", objectMapper.createArrayNode());
        return state;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.roleLabel(String)」。
    // 具体功能：「HearingCourtBootstrapService.roleLabel(String)」：构建角色标签；实际协作者为 「"MERCHANT".equalsIgnoreCase」；处理的关键状态/协议值包括 「MERCHANT」、「商家」、「用户」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.roleLabel(String)」的上游调用点包括 「HearingCourtBootstrapService.defaultClaimResolution」、「HearingCourtBootstrapService.defaultRespondentAttitude」、「HearingCourtBootstrapService.defaultDisputeCoreState」。
    // 下游影响：「HearingCourtBootstrapService.roleLabel(String)」向下依次触达 「"MERCHANT".equalsIgnoreCase」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.roleLabel(String)」负责主链路中的“角色标签”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String roleLabel(String role) {
        return "MERCHANT".equalsIgnoreCase(role) ? "商家" : "用户";
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.defaultKnownFacts(FulfillmentCaseEntity)」。
    // 具体功能：「HearingCourtBootstrapService.defaultKnownFacts(FulfillmentCaseEntity)」：构建默认已知事实；实际协作者为 「objectMapper.createArrayNode」、「dispute.getLogisticsId」、「facts.addObject」；处理的关键状态/协议值包括 「fact」、「案件关联物流单号：」、「source」、「外部导入单」，最终返回「ArrayNode」。
    // 上游调用：「HearingCourtBootstrapService.defaultKnownFacts(FulfillmentCaseEntity)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「HearingCourtBootstrapService.defaultKnownFacts(FulfillmentCaseEntity)」向下依次触达 「objectMapper.createArrayNode」、「dispute.getLogisticsId」、「facts.addObject」；计算结果以「ArrayNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.defaultKnownFacts(FulfillmentCaseEntity)」负责主链路中的“默认已知事实”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ArrayNode defaultKnownFacts(FulfillmentCaseEntity dispute) {
        ArrayNode facts = objectMapper.createArrayNode();
        if (dispute.getLogisticsId() != null && !dispute.getLogisticsId().isBlank()) {
            ObjectNode fact = facts.addObject();
            fact.put("fact", "案件关联物流单号：" + dispute.getLogisticsId());
            fact.put("source", "外部导入单");
            fact.put("support_level", "REFERENCE");
        }
        return facts;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.defaultDisputedFacts(FulfillmentCaseEntity)」。
    // 具体功能：「HearingCourtBootstrapService.defaultDisputedFacts(FulfillmentCaseEntity)」：构建默认争议事实；实际协作者为 「objectMapper.createArrayNode」、「facts.addObject」、「dispute.getDescription」；处理的关键状态/协议值包括 「fact」、「user_position」、「用户侧陈述需在庭审中确认」、「merchant_position」，最终返回「ArrayNode」。
    // 上游调用：「HearingCourtBootstrapService.defaultDisputedFacts(FulfillmentCaseEntity)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「HearingCourtBootstrapService.defaultDisputedFacts(FulfillmentCaseEntity)」向下依次触达 「objectMapper.createArrayNode」、「facts.addObject」、「dispute.getDescription」；计算结果以「ArrayNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.defaultDisputedFacts(FulfillmentCaseEntity)」负责主链路中的“默认争议事实”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ArrayNode defaultDisputedFacts(FulfillmentCaseEntity dispute) {
        ArrayNode facts = objectMapper.createArrayNode();
        ObjectNode fact = facts.addObject();
        fact.put("fact", dispute.getDescription());
        fact.put("user_position", "用户侧陈述需在庭审中确认");
        fact.put("merchant_position", "商家侧陈述需在庭审中确认");
        fact.put("importance", "HIGH");
        return facts;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.defaultPolicyHooks(FulfillmentCaseEntity)」。
    // 具体功能：「HearingCourtBootstrapService.defaultPolicyHooks(FulfillmentCaseEntity)」：构建默认政策规则Hooks；实际协作者为 「objectMapper.createArrayNode」、「dispute.getDisputeType」；处理的关键状态/协议值包括 「SIGNED」、「签收争议」、「物流异常核验」、「举证责任」，最终返回「ArrayNode」。
    // 上游调用：「HearingCourtBootstrapService.defaultPolicyHooks(FulfillmentCaseEntity)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「HearingCourtBootstrapService.defaultPolicyHooks(FulfillmentCaseEntity)」向下依次触达 「objectMapper.createArrayNode」、「dispute.getDisputeType」；计算结果以「ArrayNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.defaultPolicyHooks(FulfillmentCaseEntity)」负责主链路中的“默认政策规则Hooks”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ArrayNode defaultPolicyHooks(FulfillmentCaseEntity dispute) {
        ArrayNode hooks = objectMapper.createArrayNode();
        if (dispute.getDisputeType() != null && dispute.getDisputeType().contains("SIGNED")) {
            hooks.add("签收争议");
            hooks.add("物流异常核验");
            hooks.add("举证责任");
        }
        return hooks;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.defaultPartyEvidenceSummary()」。
    // 具体功能：「HearingCourtBootstrapService.defaultPartyEvidenceSummary()」：构建默认当事方证据Summary；实际协作者为 「objectMapper.createObjectNode」、「emptyEvidenceSideSummary」；处理的关键状态/协议值包括 「USER」、「MERCHANT」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.defaultPartyEvidenceSummary()」的上游调用点包括 「HearingCourtBootstrapService.evidenceMatrix」、「HearingCourtBootstrapService.emptyEvidenceBaseline」。
    // 下游影响：「HearingCourtBootstrapService.defaultPartyEvidenceSummary()」向下依次触达 「objectMapper.createObjectNode」、「emptyEvidenceSideSummary」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.defaultPartyEvidenceSummary()」负责主链路中的“默认当事方证据Summary”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode defaultPartyEvidenceSummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.set("USER", emptyEvidenceSideSummary());
        summary.set("MERCHANT", emptyEvidenceSideSummary());
        return summary;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.emptyEvidenceSideSummary()」。
    // 具体功能：「HearingCourtBootstrapService.emptyEvidenceSideSummary()」：构建为空证据副Summary；实际协作者为 「objectMapper.createObjectNode」、「summary.putArray」；处理的关键状态/协议值包括 「strong_points」、「weak_points」、「missing_items」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.emptyEvidenceSideSummary()」的上游调用点包括 「HearingCourtBootstrapService.defaultPartyEvidenceSummary」。
    // 下游影响：「HearingCourtBootstrapService.emptyEvidenceSideSummary()」向下依次触达 「objectMapper.createObjectNode」、「summary.putArray」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.emptyEvidenceSideSummary()」负责主链路中的“为空证据副Summary”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode emptyEvidenceSideSummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.putArray("strong_points");
        summary.putArray("weak_points");
        summary.putArray("missing_items");
        return summary;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.readJson(String)」。
    // 具体功能：「HearingCourtBootstrapService.readJson(String)」：读取JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「HearingCourtBootstrapService.readJson(String)」的上游调用点包括 「HearingCourtBootstrapService.readJsonObject」、「HearingCourtBootstrapService.readJsonArray」。
    // 下游影响：「HearingCourtBootstrapService.readJson(String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.readJson(String)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid hearing bootstrap json", exception);
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.readJsonObject(String)」。
    // 具体功能：「HearingCourtBootstrapService.readJsonObject(String)」：读取JSON对象；实际协作者为 「node.isObject」、「objectMapper.createObjectNode」、「readJson」，最终返回「JsonNode」。
    // 上游调用：「HearingCourtBootstrapService.readJsonObject(String)」只由「HearingCourtBootstrapService」内部流程使用，负责封装“JSON对象”这一步校验、映射或状态转换。
    // 下游影响：「HearingCourtBootstrapService.readJsonObject(String)」向下依次触达 「node.isObject」、「objectMapper.createObjectNode」、「readJson」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.readJsonObject(String)」统一“JSON对象”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private JsonNode readJsonObject(String json) {
        JsonNode node = readJson(json);
        return node.isObject() ? node : objectMapper.createObjectNode();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.readJsonArray(String)」。
    // 具体功能：「HearingCourtBootstrapService.readJsonArray(String)」：读取JSON数组；实际协作者为 「node.isArray」、「objectMapper.createArrayNode」、「readJson」，最终返回「JsonNode」。
    // 上游调用：「HearingCourtBootstrapService.readJsonArray(String)」只由「HearingCourtBootstrapService」内部流程使用，负责封装“JSON数组”这一步校验、映射或状态转换。
    // 下游影响：「HearingCourtBootstrapService.readJsonArray(String)」向下依次触达 「node.isArray」、「objectMapper.createArrayNode」、「readJson」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.readJsonArray(String)」统一“JSON数组”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private JsonNode readJsonArray(String json) {
        JsonNode node = readJson(json);
        return node.isArray() ? node : objectMapper.createArrayNode();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.arrayOrEmpty(JsonNode)」。
    // 具体功能：「HearingCourtBootstrapService.arrayOrEmpty(JsonNode)」：构建数组或者为空：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「node.isArray」、「node.deepCopy」、「objectMapper.createArrayNode」，最终返回「ArrayNode」。
    // 上游调用：「HearingCourtBootstrapService.arrayOrEmpty(JsonNode)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」、「HearingCourtBootstrapService.evidenceMatrix」。
    // 下游影响：「HearingCourtBootstrapService.arrayOrEmpty(JsonNode)」向下依次触达 「node.isArray」、「node.deepCopy」、「objectMapper.createArrayNode」；计算结果以「ArrayNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.arrayOrEmpty(JsonNode)」负责主链路中的“数组或者为空”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ArrayNode arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray()
                ? node.deepCopy()
                : objectMapper.createArrayNode();
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.arrayOrDefault(JsonNode,ArrayNode)」。
    // 具体功能：「HearingCourtBootstrapService.arrayOrDefault(JsonNode,ArrayNode)」：构建数组或者默认：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「node.isArray」、「node.deepCopy」，最终返回「ArrayNode」。
    // 上游调用：「HearingCourtBootstrapService.arrayOrDefault(JsonNode,ArrayNode)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「HearingCourtBootstrapService.arrayOrDefault(JsonNode,ArrayNode)」向下依次触达 「node.isArray」、「node.deepCopy」；计算结果以「ArrayNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.arrayOrDefault(JsonNode,ArrayNode)」负责主链路中的“数组或者默认”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ArrayNode arrayOrDefault(JsonNode node, ArrayNode fallback) {
        return node != null && node.isArray() ? node.deepCopy() : fallback;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.objectOrDefault(JsonNode,ObjectNode)」。
    // 具体功能：「HearingCourtBootstrapService.objectOrDefault(JsonNode,ObjectNode)」：构建对象或者默认：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「node.isObject」、「node.deepCopy」，最终返回「ObjectNode」。
    // 上游调用：「HearingCourtBootstrapService.objectOrDefault(JsonNode,ObjectNode)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」、「HearingCourtBootstrapService.evidenceMatrix」。
    // 下游影响：「HearingCourtBootstrapService.objectOrDefault(JsonNode,ObjectNode)」向下依次触达 「node.isObject」、「node.deepCopy」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.objectOrDefault(JsonNode,ObjectNode)」负责主链路中的“对象或者默认”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private ObjectNode objectOrDefault(JsonNode node, ObjectNode fallback) {
        return node != null && node.isObject() ? node.deepCopy() : fallback;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.firstText(JsonNode,String)」。
    // 具体功能：「HearingCourtBootstrapService.firstText(JsonNode,String)」：构建首版文本；实际协作者为 「source.path(field).asText」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.firstText(JsonNode,String)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」、「HearingCourtBootstrapService.evidenceMatrix」、「HearingCourtBootstrapService.defaultClaim」、「HearingCourtBootstrapService.defaultClaimResolution」。
    // 下游影响：「HearingCourtBootstrapService.firstText(JsonNode,String)」向下依次触达 「source.path(field).asText」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.firstText(JsonNode,String)」负责主链路中的“首版文本”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String firstText(JsonNode source, String fallback, String... fields) {
        for (String field : fields) {
            String value = source.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.caseStoryText(JsonNode,String)」。
    // 具体功能：「HearingCourtBootstrapService.caseStoryText(JsonNode,String)」：构建案件Story文本；实际协作者为 「caseStory.isTextual」、「caseStory.asText」、「caseStory.isObject」、「firstText」；处理的关键状态/协议值包括 「case_story」、「one_sentence_summary」、「neutral_summary」、「title」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.caseStoryText(JsonNode,String)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」。
    // 下游影响：「HearingCourtBootstrapService.caseStoryText(JsonNode,String)」向下依次触达 「caseStory.isTextual」、「caseStory.asText」、「caseStory.isObject」、「firstText」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.caseStoryText(JsonNode,String)」负责主链路中的“案件Story文本”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String caseStoryText(JsonNode source, String fallback) {
        JsonNode caseStory = source.path("case_story");
        if (caseStory.isTextual() && !caseStory.asText().isBlank()) {
            return caseStory.asText();
        }
        if (caseStory.isObject()) {
            String oneSentence = caseStory.path("one_sentence_summary").asText("");
            if (!oneSentence.isBlank()) {
                return oneSentence;
            }
            String neutralSummary = caseStory.path("neutral_summary").asText("");
            if (!neutralSummary.isBlank()) {
                return neutralSummary;
            }
            String title = caseStory.path("title").asText("");
            if (!title.isBlank()) {
                return title;
            }
        }
        return firstText(source, fallback, "summary", "case_summary", "neutral_summary");
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.sharedCourtAudienceJson()」。
    // 具体功能：「HearingCourtBootstrapService.sharedCourtAudienceJson()」：计算 SHA-shared法庭受众 JSONJSON；实际协作者为 「json」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.sharedCourtAudienceJson()」的上游调用点包括 「HearingCourtBootstrapService.appendAgentMessageIfAbsent」。
    // 下游影响：「HearingCourtBootstrapService.sharedCourtAudienceJson()」向下依次触达 「json」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.sharedCourtAudienceJson()」负责主链路中的“shared法庭受众 JSONJSON”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String sharedCourtAudienceJson() {
        return json(
                List.of(
                        ActorRole.USER.name(),
                        ActorRole.MERCHANT.name(),
                        ActorRole.CUSTOMER_SERVICE.name(),
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ActorRole.ADMIN.name(),
                        ActorRole.SYSTEM.name()));
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.json(Object)」。
    // 具体功能：「HearingCourtBootstrapService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.json(Object)」的上游调用点包括 「HearingCourtBootstrapService.emptyEvidenceBaseline」、「HearingCourtBootstrapService.sharedCourtAudienceJson」。
    // 下游影响：「HearingCourtBootstrapService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize hearing bootstrap payload", exception);
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.assertActorCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」。
    // 具体功能：「HearingCourtBootstrapService.assertActorCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」：断言操作者Can访问；实际协作者为 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」；不满足前置条件时抛出 「SecurityException」，最终返回「void」。
    // 上游调用：「HearingCourtBootstrapService.assertActorCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.assertActorCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」、「dispute.getUserId」、「dispute.getMerchantId」。
    // 系统意义：「HearingCourtBootstrapService.assertActorCanAccess(FulfillmentCaseEntity,AuthenticatedActor)」在“操作者Can访问”进入下游前阻断非法状态；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static void assertActorCanAccess(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new SecurityException("actor cannot bootstrap hearing");
        }
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.canOpenOrRefreshBootstrap(FulfillmentCaseEntity)」。
    // 具体功能：「HearingCourtBootstrapService.canOpenOrRefreshBootstrap(FulfillmentCaseEntity)」：判断能否Open或者Refresh开庭装卷；实际协作者为 「dispute.getCaseStatus」，最终返回「boolean」。
    // 上游调用：「HearingCourtBootstrapService.canOpenOrRefreshBootstrap(FulfillmentCaseEntity)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.canOpenOrRefreshBootstrap(FulfillmentCaseEntity)」向下依次触达 「dispute.getCaseStatus」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.canOpenOrRefreshBootstrap(FulfillmentCaseEntity)」负责主链路中的“Open或者Refresh开庭装卷”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static boolean canOpenOrRefreshBootstrap(FulfillmentCaseEntity dispute) {
        return dispute.getCaseStatus() == CaseStatus.HEARING_OPEN
                || dispute.getCaseStatus() == CaseStatus.HEARING;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.canReadExistingBootstrappedCourt(FulfillmentCaseEntity)」。
    // 具体功能：「HearingCourtBootstrapService.canReadExistingBootstrappedCourt(FulfillmentCaseEntity)」：判断能否ReadExistingBootstrapped法庭；实际协作者为 「dispute.getRouteType」、「dispute.getCurrentRoom」、「dispute.getCaseStatus」；处理的关键状态/协议值包括 「HEARING」，最终返回「boolean」。
    // 上游调用：「HearingCourtBootstrapService.canReadExistingBootstrappedCourt(FulfillmentCaseEntity)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.canReadExistingBootstrappedCourt(FulfillmentCaseEntity)」向下依次触达 「dispute.getRouteType」、「dispute.getCurrentRoom」、「dispute.getCaseStatus」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.canReadExistingBootstrappedCourt(FulfillmentCaseEntity)」负责主链路中的“ReadExistingBootstrapped法庭”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static boolean canReadExistingBootstrappedCourt(FulfillmentCaseEntity dispute) {
        if (dispute.getRouteType() != RouteType.FULL_HEARING
                && !"HEARING".equals(dispute.getCurrentRoom())) {
            return false;
        }
        return switch (dispute.getCaseStatus()) {
            case WAITING_EVIDENCE,
                    SETTLEMENT_PENDING,
                    DRAFT_READY,
                    DELIBERATION_RUNNING,
                    REVIEW_PENDING,
                    REMEDY_PLANNED,
                    WAITING_HUMAN_REVIEW,
                    MANUAL_HANDOFF,
                    APPROVED_FOR_EXECUTION,
                    EXECUTING,
                    CLOSED -> true;
            default -> false;
        };
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.respondentRole(ActorRole)」。
    // 具体功能：「HearingCourtBootstrapService.respondentRole(ActorRole)」：构建被申请方角色，最终返回「ActorRole」。
    // 上游调用：「HearingCourtBootstrapService.respondentRole(ActorRole)」的上游调用点包括 「HearingCourtBootstrapService.intakeFactMap」、「HearingCourtBootstrapService.defaultRespondentAttitude」、「HearingCourtBootstrapService.defaultDisputeCoreState」。
    // 下游影响：「HearingCourtBootstrapService.respondentRole(ActorRole)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「ActorRole」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.respondentRole(ActorRole)」负责主链路中的“被申请方角色”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static ActorRole respondentRole(ActorRole initiatorRole) {
        return initiatorRole == ActorRole.MERCHANT ? ActorRole.USER : ActorRole.MERCHANT;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.bootstrapMessageKey(String,String)」。
    // 具体功能：「HearingCourtBootstrapService.bootstrapMessageKey(String,String)」：初始化消息键；处理的关键状态/协议值包括 「hearing-bootstrap:」、「:」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.bootstrapMessageKey(String,String)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.bootstrapMessageKey(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.bootstrapMessageKey(String,String)」负责主链路中的“消息键”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String bootstrapMessageKey(String caseId, String messageName) {
        return "hearing-bootstrap:" + caseId + ":" + messageName;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.containsUnmappedRelation(JsonNode)」。
    // 具体功能：「HearingCourtBootstrapService.containsUnmappedRelation(JsonNode)」：判断是否包含未映射关联；实际协作者为 「matrix.isArray」、「"UNMAPPED".equalsIgnoreCase」、「item.path("relation_type").asText」；处理的关键状态/协议值包括 「UNMAPPED」、「relation_type」，最终返回「boolean」。
    // 上游调用：「HearingCourtBootstrapService.containsUnmappedRelation(JsonNode)」的上游调用点包括 「HearingCourtBootstrapService.courtroomContext」。
    // 下游影响：「HearingCourtBootstrapService.containsUnmappedRelation(JsonNode)」向下依次触达 「matrix.isArray」、「"UNMAPPED".equalsIgnoreCase」、「item.path("relation_type").asText」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.containsUnmappedRelation(JsonNode)」负责主链路中的“未映射关联”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static boolean containsUnmappedRelation(JsonNode matrix) {
        if (!matrix.isArray()) {
            return false;
        }
        for (JsonNode item : matrix) {
            if ("UNMAPPED".equalsIgnoreCase(item.path("relation_type").asText(""))) {
                return true;
            }
        }
        return false;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.nullToEmpty(String)」。
    // 具体功能：「HearingCourtBootstrapService.nullToEmpty(String)」：构建空值为空，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.nullToEmpty(String)」的上游调用点包括 「HearingCourtBootstrapService.courtroomContext」。
    // 下游影响：「HearingCourtBootstrapService.nullToEmpty(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.nullToEmpty(String)」负责主链路中的“空值为空”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.judgeRoundOpeningKey(String)」。
    // 具体功能：「HearingCourtBootstrapService.judgeRoundOpeningKey(String)」：构建法官轮次开场消息键；处理的关键状态/协议值包括 「judge-round-opening:」、「:1」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.judgeRoundOpeningKey(String)」的上游调用点包括 「HearingCourtBootstrapService.bootstrap」。
    // 下游影响：「HearingCourtBootstrapService.judgeRoundOpeningKey(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.judgeRoundOpeningKey(String)」负责主链路中的“法官轮次开场消息键”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String judgeRoundOpeningKey(String caseId) {
        return "judge-round-opening:" + caseId + ":1";
    }

    // 所属模块：【共享小法庭 / 应用编排层】「HearingCourtBootstrapService.compactUuid()」。
    // 具体功能：「HearingCourtBootstrapService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「HearingCourtBootstrapService.compactUuid()」的上游调用点包括 「HearingCourtBootstrapService.ensureHearingState」、「HearingCourtBootstrapService.emptyEvidenceBaseline」、「HearingCourtBootstrapService.recordSnapshotIfAbsent」、「HearingCourtBootstrapService.appendAgentMessageIfAbsent」。
    // 下游影响：「HearingCourtBootstrapService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「HearingCourtBootstrapService.compactUuid()」负责主链路中的“UUID”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
