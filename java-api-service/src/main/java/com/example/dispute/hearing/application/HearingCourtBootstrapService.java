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

    private EvidenceDossierEntity ensureEvidenceBaseline(String caseId) {
        return evidenceDossierRepository
                .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                .orElseGet(() -> evidenceDossierRepository.save(emptyEvidenceBaseline(caseId)));
    }

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

    private ObjectNode openingMessage(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("sender_role", role);
        message.put("content", content);
        return message;
    }

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

    private String judgeOpeningText(FulfillmentCaseEntity dispute) {
        return "现在开庭。小法庭将基于接待室案情卷宗、证据室证据卷宗和双方庭审陈述进行三轮结构化审理。"
                + "本案当前争议为："
                + dispute.getTitle()
                + "。AI 法官输出为裁决方案草案，最终结果以后续确认为准。";
    }

    private static String intakeAnnouncementText(ObjectNode intakeFactMap) {
        return "案情接待官宣读案情卷宗："
                + intakeFactMap.path("case_story").asText()
                + " 主要争议事实为："
                + firstArrayFact(intakeFactMap.path("disputed_facts"))
                + "。庭审交接备注："
                + intakeFactMap.path("handoff_notes").asText();
    }

    private static String evidenceAnnouncementText(ObjectNode evidenceMatrix) {
        return "证据书记官宣读证据卷宗：当前证据总体置信度为 "
                + evidenceMatrix.path("overall_confidence_score").asInt(0)
                + "/100。核心证明矩阵显示："
                + firstArrayFact(evidenceMatrix.path("fact_evidence_matrix"))
                + "。证据交接备注："
                + evidenceMatrix.path("handoff_notes").asText();
    }

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

    private static String localizedRelationType(String relationType) {
        return switch (relationType == null ? "" : relationType.toUpperCase()) {
            case "UNMAPPED", "UNKNOWN", "" -> "尚未映射到具体争议事实";
            case "SUPPORTS", "SUPPORTING", "SUPPORT" -> "支持相关争议事实";
            case "OPPOSES", "OPPOSING", "REFUTES" -> "反驳相关争议事实";
            case "PARTIAL", "PARTIALLY_SUPPORTS" -> "与相关争议事实存在部分关联";
            default -> "已关联到争议事实";
        };
    }

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

    private static String localizedEvidenceStrength(String evidenceStrength) {
        return switch (evidenceStrength == null ? "" : evidenceStrength.toUpperCase()) {
            case "HIGH", "STRONG" -> "较强";
            case "MEDIUM" -> "中等";
            case "LOW", "WEAK" -> "较弱";
            case "NONE", "UNKNOWN", "" -> "";
            default -> "待评估";
        };
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private ObjectNode defaultClaim(JsonNode source) {
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put(
                "requested_resolution",
                firstText(source, "待庭审确认", "requested_resolution", "resolution"));
        claim.putNull("amount");
        claim.put("non_monetary_request", "核验争议事实与证据链路");
        return claim;
    }

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

    private ArrayNode defaultDisputedFacts(FulfillmentCaseEntity dispute) {
        ArrayNode facts = objectMapper.createArrayNode();
        ObjectNode fact = facts.addObject();
        fact.put("fact", dispute.getDescription());
        fact.put("user_position", "用户侧陈述需在庭审中确认");
        fact.put("merchant_position", "商家侧陈述需在庭审中确认");
        fact.put("importance", "HIGH");
        return facts;
    }

    private ArrayNode defaultPolicyHooks(FulfillmentCaseEntity dispute) {
        ArrayNode hooks = objectMapper.createArrayNode();
        if (dispute.getDisputeType() != null && dispute.getDisputeType().contains("SIGNED")) {
            hooks.add("签收争议");
            hooks.add("物流异常核验");
            hooks.add("举证责任");
        }
        return hooks;
    }

    private ObjectNode defaultPartyEvidenceSummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.set("USER", emptyEvidenceSideSummary());
        summary.set("MERCHANT", emptyEvidenceSideSummary());
        return summary;
    }

    private ObjectNode emptyEvidenceSideSummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.putArray("strong_points");
        summary.putArray("weak_points");
        summary.putArray("missing_items");
        return summary;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid hearing bootstrap json", exception);
        }
    }

    private JsonNode readJsonObject(String json) {
        JsonNode node = readJson(json);
        return node.isObject() ? node : objectMapper.createObjectNode();
    }

    private JsonNode readJsonArray(String json) {
        JsonNode node = readJson(json);
        return node.isArray() ? node : objectMapper.createArrayNode();
    }

    private ArrayNode arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray()
                ? node.deepCopy()
                : objectMapper.createArrayNode();
    }

    private ArrayNode arrayOrDefault(JsonNode node, ArrayNode fallback) {
        return node != null && node.isArray() ? node.deepCopy() : fallback;
    }

    private ObjectNode objectOrDefault(JsonNode node, ObjectNode fallback) {
        return node != null && node.isObject() ? node.deepCopy() : fallback;
    }

    private static String firstText(JsonNode source, String fallback, String... fields) {
        for (String field : fields) {
            String value = source.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize hearing bootstrap payload", exception);
        }
    }

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

    private static boolean canOpenOrRefreshBootstrap(FulfillmentCaseEntity dispute) {
        return dispute.getCaseStatus() == CaseStatus.HEARING_OPEN
                || dispute.getCaseStatus() == CaseStatus.HEARING;
    }

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

    private static ActorRole respondentRole(ActorRole initiatorRole) {
        return initiatorRole == ActorRole.MERCHANT ? ActorRole.USER : ActorRole.MERCHANT;
    }

    private static String bootstrapMessageKey(String caseId, String messageName) {
        return "hearing-bootstrap:" + caseId + ":" + messageName;
    }

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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String judgeRoundOpeningKey(String caseId) {
        return "judge-round-opening:" + caseId + ":1";
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
