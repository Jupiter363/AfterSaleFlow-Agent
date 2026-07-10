package com.example.dispute.hearing.application;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HearingCourtOrchestrator {

    public static final String JUDGE_SENDER_ROLE = "JUDGE";
    public static final String JUDGE_SENDER_ID = "presiding-judge";

    private static final Logger log = LoggerFactory.getLogger(HearingCourtOrchestrator.class);

    private final FulfillmentCaseRepository caseRepository;
    private final CaseRoomRepository roomRepository;
    private final HearingRoundRepository roundRepository;
    private final HearingRoundPartySubmissionRepository submissionRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService eventService;
    private final HearingCourtAgentClient agentClient;
    private final AgentA2AMessageService a2aMessageService;
    private final ActiveCourtroomContextAssembler courtroomContextAssembler;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PostCommitSideEffectExecutor postCommit;
    private final TransactionTemplate courtTransaction;

    public HearingCourtOrchestrator(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            HearingCourtAgentClient agentClient,
            AgentA2AMessageService a2aMessageService,
            ActiveCourtroomContextAssembler courtroomContextAssembler,
            ObjectMapper objectMapper,
            Clock clock,
            PostCommitSideEffectExecutor postCommit,
            TransactionTemplate courtTransaction) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.roundRepository = roundRepository;
        this.submissionRepository = submissionRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.agentClient = agentClient;
        this.a2aMessageService = a2aMessageService;
        this.courtroomContextAssembler = courtroomContextAssembler;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.postCommit = postCommit;
        this.courtTransaction = courtTransaction;
    }

    public void afterRoundOpenedAfterCommit(String caseId, int roundNo, String traceId) {
        postCommit.execute(
                "hearing-court-round-opened",
                Map.of("case_id", caseId, "round_no", roundNo),
                () -> afterRoundOpened(caseId, roundNo, traceId));
    }

    public void afterRoundClosedAfterCommit(
            String caseId, int roundNo, boolean finalRound, String traceId) {
        afterRoundClosedAfterCommit(caseId, roundNo, finalRound, traceId, () -> {});
    }

    public void afterRoundClosedAfterCommit(
            String caseId,
            int roundNo,
            boolean finalRound,
            String traceId,
            Runnable completion) {
        postCommit.execute(
                "hearing-court-round-closed",
                Map.of("case_id", caseId, "round_no", roundNo, "final_round", finalRound),
                () -> {
                    afterRoundClosed(caseId, roundNo, finalRound, traceId);
                    completion.run();
                });
    }

    public void afterRoundOpened(String caseId, int roundNo, String traceId) {
        processJudgeTurn(
                caseId,
                roundNo,
                false,
                judgeRoundOpeningKey(caseId, roundNo),
                "judge-round-opening-ready:" + caseId + ":" + roundNo,
                traceId);
    }

    public void afterRoundClosed(
            String caseId, int roundNo, boolean finalRound, String traceId) {
        processJudgeTurn(
                caseId,
                roundNo,
                finalRound,
                judgeRoundTurnKey(caseId, roundNo),
                "judge-round-turn-ready:" + caseId + ":" + roundNo,
                traceId);
        if (finalRound && !hasCompleteFormalJuryReport(caseId, roundNo)) {
            throw new IllegalStateException(
                    "final hearing convergence requires both formal jury A2A and room report");
        }
    }

    private void processJudgeTurn(
            String caseId,
            int roundNo,
            boolean finalRound,
            String idempotencyKey,
            String lifecycleEventKey,
            String traceId) {
        TurnPreparation preparation =
                courtTransaction.execute(
                        ignored ->
                                prepareJudgeTurn(
                                        caseId,
                                        roundNo,
                                        finalRound,
                                        idempotencyKey,
                                        lifecycleEventKey));
        if (preparation == null || preparation.complete()) {
            return;
        }
        HearingCourtAgentResult generated =
                preparation.command() == null
                        ? null
                        : safeGenerate(preparation.command(), traceId);
        courtTransaction.executeWithoutResult(
                ignored -> persistJudgeTurn(preparation, generated, traceId));
    }

    private TurnPreparation prepareJudgeTurn(
            String caseId,
            int roundNo,
            boolean finalRound,
            String idempotencyKey,
            String lifecycleEventKey) {
        var existingJudgeMessage =
                messageRepository.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey);
        if (existingJudgeMessage.isPresent()
                && (!finalRound || hasCompleteFormalJuryReport(caseId, roundNo))) {
            return TurnPreparation.completed(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey);
        }
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        HearingRoundEntity round =
                roundRepository
                        .findByCaseIdAndRoundNo(caseId, roundNo)
                        .orElseThrow(() -> new IllegalArgumentException("hearing round not found"));
        List<HearingRoundPartySubmissionEntity> submissions =
                submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        caseId, roundNo);
        if (existingJudgeMessage.isPresent()) {
            return TurnPreparation.repair(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey,
                    reviewFocusSignal(submissions));
        }
        HearingCourtAgentCommand command = command(dispute, round, submissions, finalRound);
        return TurnPreparation.generate(
                caseId,
                roundNo,
                finalRound,
                idempotencyKey,
                lifecycleEventKey,
                command);
    }

    private void persistJudgeTurn(
            TurnPreparation preparation,
            HearingCourtAgentResult generated,
            String traceId) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(preparation.caseId())
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomTypeForUpdate(
                                preparation.caseId(), RoomType.HEARING)
                        .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
        roundRepository
                .findByCaseIdAndRoundNoForUpdate(
                        preparation.caseId(), preparation.roundNo())
                .orElseThrow(() -> new IllegalArgumentException("hearing round not found"));

        Optional<RoomMessageEntity> existingJudgeMessage =
                messageRepository.findByCaseIdAndIdempotencyKey(
                        preparation.caseId(), preparation.idempotencyKey());
        HearingCourtAgentResult effectiveResult = generated;
        if (existingJudgeMessage.isEmpty()) {
            if (effectiveResult == null) {
                throw new IllegalStateException("judge turn disappeared during repair");
            }
            RoomMessageEntity saved =
                    appendJudgeMessage(
                            dispute,
                            room,
                            preparation.roundNo(),
                            effectiveResult,
                            preparation.idempotencyKey(),
                            traceId);
            eventService.recordRoomMessage(
                    dispute.getId(),
                    room.getId(),
                    saved.getId(),
                    saved.getMessageText(),
                    saved.getAudienceJson(),
                    saved.getAudienceActorIdsJson(),
                    JUDGE_SENDER_ID);
            eventService.recordLifecycleEvent(
                    dispute.getId(),
                    room.getId(),
                    effectiveResult.courtEventType(),
                    judgeLifecyclePayload(effectiveResult),
                    preparation.lifecycleEventKey(),
                    JUDGE_SENDER_ID);
        }

        boolean finalDraftRequired =
                preparation.finalRound()
                        || effectiveResult != null && effectiveResult.finalDraftRequired();
        if (!finalDraftRequired) {
            return;
        }
        appendFormalJuryReportIfNeeded(
                dispute,
                room,
                preparation.roundNo(),
                effectiveResult == null
                        ? "FINAL_DRAFT_REQUIRED"
                        : effectiveResult.courtEventType(),
                effectiveResult == null
                        ? preparation.recoveryReviewFocus()
                        : effectiveResult.reviewFocusSignal(),
                effectiveResult == null
                        ? "hearing-round-recovery-v1"
                        : effectiveResult.promptVersion(),
                traceId);
    }

    private Map<String, Object> judgeLifecyclePayload(HearingCourtAgentResult result) {
        return Map.of(
                "round_no", result.roundNo(),
                "next_round_no", result.nextRoundNo() == null ? "" : result.nextRoundNo(),
                "final_draft_required", result.finalDraftRequired(),
                "round_summary", result.roundSummary(),
                "questions_for_user", result.questionsForUser(),
                "questions_for_merchant", result.questionsForMerchant(),
                "review_focus_signal", result.reviewFocusSignal(),
                "prompt_version", result.promptVersion(),
                "model", result.model());
    }

    private HearingCourtAgentCommand command(
            FulfillmentCaseEntity dispute,
            HearingRoundEntity round,
            List<HearingRoundPartySubmissionEntity> submissions,
            boolean finalRound) {
        return new HearingCourtAgentCommand(
                dispute.getId(),
                defaultText(dispute.getCurrentWorkflowId(), "hearing-window-" + dispute.getId()),
                dispute.getOrderId(),
                dispute.getAfterSaleId(),
                dispute.getLogisticsId(),
                dispute.getDisputeType(),
                dispute.getTitle(),
                dispute.getDescription(),
                dispute.getRiskLevel() == null ? "MEDIUM" : dispute.getRiskLevel().name(),
                round.getRoundNo(),
                round.getDossierVersion(),
                finalRound,
                round.getRoundStatus().name(),
                round.getStopReason() == null ? null : round.getStopReason().name(),
                defaultText(round.getSummaryJson(), "{}"),
                courtroomContextJson(dispute.getId(), round.getRoundNo()),
                submissions.stream()
                        .map(
                                submission ->
                                        new HearingCourtAgentCommand.PartySubmission(
                                                submission.getParticipantRole().name(),
                                                participantId(dispute, submission.getParticipantRole()),
                                                submission.getSubmissionSource().name(),
                                                defaultText(submission.getSubmissionJson(), "{}")))
                        .toList());
    }

    private String courtroomContextJson(String caseId, int roundNo) {
        return json(courtroomContextAssembler.assemble(caseId, roundNo));
    }

    private HearingCourtAgentResult safeGenerate(HearingCourtAgentCommand command, String traceId) {
        try {
            return agentClient.generateRoundTurn(
                    command,
                    traceId,
                    "REQ_HEARING_ROUND_" + command.caseId() + "_" + command.roundNo());
        } catch (RuntimeException failure) {
            log.warn(
                    "Hearing court round turn degraded: case_id={}, round_no={}, trace_id={}",
                    command.caseId(),
                    command.roundNo(),
                    traceId,
                    failure);
            return fallback(command);
        }
    }

    private HearingCourtAgentResult fallback(HearingCourtAgentCommand command) {
        boolean opening =
                command.partySubmissions().isEmpty()
                        && "OPEN".equals(command.roundStatus())
                        && command.stopReason() == null;
        boolean finalRound = command.finalRound();
        if (opening) {
            return new HearingCourtAgentResult(
                    JUDGE_SENDER_ROLE,
                    "小法庭现在开庭。第 1 轮请双方先围绕接待室卷宗和证据室材料说明关键事实：用户侧请说明争议发生经过、签收或验货情况以及希望平台核验的重点；商家侧请说明履约记录、发货/物流交接情况以及与用户主张不一致的部分。",
                    "法官已打开第 1 轮事实陈述，等待用户和商家分别提交本轮说明。",
                    List.of("请说明争议发生经过、签收或验货情况，以及希望平台优先核验的事实。"),
                    List.of("请说明履约记录、发货/物流交接情况，以及与用户主张不一致的事实。"),
                    "JUDGE_OPENING_READY",
                    command.roundNo(),
                    command.roundNo(),
                    false,
                    "hearing-round-opening-fallback-v1",
                    "local-fallback");
        }
        if (finalRound) {
            return new HearingCourtAgentResult(
                    JUDGE_SENDER_ROLE,
                    "第 3 轮陈述已封存。AI 法官将基于当前案情、证据和双方陈述形成非最终裁决草案，并进入裁决草案与后续确认路径。",
                    "模型暂不可用，系统已封存最终轮材料并进入裁决草案生成路径。",
                    List.of(),
                    List.of(),
                    "FINAL_DRAFT_REQUIRED",
                    command.roundNo(),
                    null,
                    true,
                    reviewFocusSignal(command),
                    "hearing-round-turn-fallback-v1",
                    "local-fallback");
        }
        return new HearingCourtAgentResult(
                JUDGE_SENDER_ROLE,
                "本轮庭审陈述已封存。下一轮将继续围绕争议焦点进行定向说明，双方可分别补充与本案事实和证据相关的陈述。",
                "模型暂不可用，系统已按结构化庭审流程封存本轮材料。",
                List.of("请围绕法官上一轮问题补充客观事实、证据来源和时间线。"),
                List.of("请围绕履约记录、证据来源和与用户主张的差异补充说明。"),
                "JUDGE_NEXT_QUESTIONS_READY",
                command.roundNo(),
                command.roundNo() + 1,
                false,
                List.of(),
                "hearing-round-turn-fallback-v1",
                "local-fallback");
    }

    private List<String> reviewFocusSignal(HearingCourtAgentCommand command) {
        if (!command.finalRound()) {
            return List.of();
        }
        return command.partySubmissions().stream()
                .map(this::reviewFocusSignal)
                .filter(signal -> !signal.isBlank())
                .limit(20)
                .toList();
    }

    private List<String> reviewFocusSignal(
            List<HearingRoundPartySubmissionEntity> submissions) {
        return submissions.stream()
                .map(
                        submission ->
                                new HearingCourtAgentCommand.PartySubmission(
                                        submission.getParticipantRole().name(),
                                        "",
                                        submission.getSubmissionSource().name(),
                                        defaultText(submission.getSubmissionJson(), "{}")))
                .map(this::reviewFocusSignal)
                .filter(signal -> !signal.isBlank())
                .limit(20)
                .toList();
    }

    private String reviewFocusSignal(HearingCourtAgentCommand.PartySubmission submission) {
        String statement = statementFromSubmissionJson(submission.submissionJson());
        if (statement.isBlank()) {
            return "";
        }
        String role = submission.participantRole() == null ? "" : submission.participantRole();
        if ("USER".equalsIgnoreCase(role)) {
            if (statement.contains("认可")
                    && statement.contains("退款")
                    && statement.contains("签收人身份")) {
                return "用户认可退款方向，但要求复核签收人身份是否已核验清楚。";
            }
            return thirdPersonReviewFocus("用户", statement);
        }
        if ("MERCHANT".equalsIgnoreCase(role)) {
            if (statement.contains("不同意退款") && statement.contains("物流签收")) {
                return "商家不同意退款，主张物流签收记录足以证明已履约。";
            }
            return thirdPersonReviewFocus("商家", statement);
        }
        return thirdPersonReviewFocus("当事人", statement);
    }

    private String statementFromSubmissionJson(String submissionJson) {
        try {
            JsonNode node = objectMapper.readTree(defaultText(submissionJson, "{}"));
            if (node.isObject()) {
                for (String field : List.of("statement", "content", "message", "text")) {
                    String value = node.path(field).asText("");
                    if (!value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        } catch (JsonProcessingException ignored) {
            return defaultText(submissionJson, "").trim();
        }
        return "";
    }

    private String thirdPersonReviewFocus(String roleLabel, String statement) {
        String normalized =
                statement
                        .replace("我方", roleLabel)
                        .replace("我们", roleLabel)
                        .replace("我", roleLabel)
                        .replaceAll("[。；;\\s]+$", "");
        if (!normalized.startsWith(roleLabel)) {
            normalized = roleLabel + "提出：" + normalized;
        }
        if (normalized.length() > 180) {
            normalized = normalized.substring(0, 180);
        }
        return normalized + "。";
    }

    private void appendFormalJuryReportIfNeeded(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            int roundNo,
            String judgeEventType,
            List<String> reviewFocusSignal,
            String promptVersion,
            String traceId) {
        if (roundNo < 3) {
            return;
        }
        String idempotencyKey = juryReviewReportKey(dispute.getId(), roundNo);
        Optional<RoomMessageEntity> roomReport =
                messageRepository
                        .findByCaseIdAndIdempotencyKey(dispute.getId(), idempotencyKey);
        Optional<AgentA2AMessageView> a2aReport =
                a2aMessageService.findFormalJuryReviewReport(dispute.getId(), roundNo);
        if (roomReport.isPresent() && a2aReport.isPresent()) {
            return;
        }
        Map<String, Object> generatedInputRefs =
                Map.of(
                        "round_no", roundNo,
                        "judge_event_type", judgeEventType,
                        "review_focus_signal", reviewFocusSignal,
                        "prompt_version", promptVersion);
        AgentA2AMessageView survivingA2A =
                a2aReport.orElseGet(
                        () -> {
                            Map<String, Object> payload =
                                    roomReport
                                            .map(
                                                    message ->
                                                            jsonObject(
                                                                    message.getMessageText(),
                                                                    "jury room report"))
                                            .orElseGet(
                                                    () ->
                                                            juryReviewPayload(
                                                                    reviewFocusSignal));
                            return a2aMessageService.record(
                    new AgentA2ACommand(
                            dispute.getId(),
                            roundNo,
                            "JURY_PANEL",
                            AgentA2AMessageService.PRESIDING_JUDGE,
                            "JURY_REVIEW_REPORT",
                                            generatedInputRefs,
                            payload,
                            "REVIEWER_VISIBLE",
                                            null));
                        });
        if (roomReport.isPresent()) {
            return;
        }
        Map<String, Object> persistedInputRefs =
                jsonObject(survivingA2A.inputRefsJson(), "formal jury A2A input refs");
        Map<String, Object> persistedPayload =
                jsonObject(survivingA2A.payloadJson(), "formal jury A2A payload");
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        RoomMessageEntity saved =
                messageRepository.save(
                        RoomMessageEntity.create(
                                "MESSAGE_" + compactUuid(),
                                dispute.getId(),
                                room.getId(),
                                sequence,
                                MessageSenderType.AGENT,
                                "JURY",
                                "jury-panel",
                                sharedCourtAudienceJson(),
                                "[]",
                                MessageType.JURY_REVIEW_REPORT,
                                survivingA2A.payloadJson(),
                                "[]",
                                idempotencyKey,
                                roundNo,
                                Instant.now(clock),
                                traceId));
        eventService.recordRoomMessage(
                dispute.getId(),
                room.getId(),
                saved.getId(),
                saved.getMessageText(),
                saved.getAudienceJson(),
                saved.getAudienceActorIdsJson(),
                "jury-panel");
        Object reviewFocus =
                persistedInputRefs.getOrDefault(
                        "review_focus_signal",
                        persistedPayload.getOrDefault(
                                "review_focus_signal", List.of()));
        Map<String, Object> lifecyclePayload = new LinkedHashMap<>();
        lifecyclePayload.put("round_no", roundNo);
        lifecyclePayload.put("visibility", survivingA2A.visibility());
        lifecyclePayload.put("review_focus_signal", reviewFocus);
        lifecyclePayload.put(
                "risk_level",
                persistedPayload.getOrDefault("risk_level", "MEDIUM"));
        lifecyclePayload.put(
                "confidence_score",
                persistedPayload.getOrDefault("confidence_score", 0));
        eventService.recordLifecycleEvent(
                dispute.getId(),
                room.getId(),
                "JURY_REVIEW_REPORT_READY",
                lifecyclePayload,
                "jury-review-report-ready:" + dispute.getId() + ":" + roundNo,
                "jury-panel");
    }

    public boolean hasCompleteFormalJuryReport(String caseId, int roundNo) {
        return messageRepository
                        .findByCaseIdAndIdempotencyKey(
                                caseId, juryReviewReportKey(caseId, roundNo))
                        .isPresent()
                && a2aMessageService.hasFormalJuryReviewReport(caseId, roundNo);
    }

    private Map<String, Object> juryReviewPayload(List<String> reviewFocusSignal) {
        List<String> focus =
                reviewFocusSignal == null || reviewFocusSignal.isEmpty()
                        ? List.of("第三轮未形成明确异议，仍需法官在草案中说明事实采信和证据依据。")
                        : List.copyOf(reviewFocusSignal);
        return Map.of(
                "summary",
                "评审团已完成第三轮复核，报告已交由法官生成裁决草案时参考。",
                "risk_level",
                focus.size() >= 3 ? "HIGH" : "MEDIUM",
                "confidence_score",
                75,
                "review_focus_signal",
                focus,
                "recommendations",
                List.of(
                        "请法官在裁决草案中逐项回应第三轮复核关注点。",
                        "请避免直接采纳单方自然语言意见，应结合案情卷宗和证据矩阵复核。"),
                "review_notes",
                "评审团复核报告是风险和遗漏视野补充，不是二次裁决主体。",
                "visibility",
                "REVIEWER_VISIBLE");
    }

    private Map<String, Object> jsonObject(String value, String label) {
        try {
            JsonNode node = objectMapper.readTree(defaultText(value, "{}"));
            if (!node.isObject()) {
                throw new IllegalStateException(label + " must be a JSON object");
            }
            return objectMapper.convertValue(
                    node, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid " + label, exception);
        }
    }

    private RoomMessageEntity appendJudgeMessage(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            int roundNo,
            HearingCourtAgentResult result,
            String idempotencyKey,
            String traceId) {
        long sequence = messageRepository.findMaxSequenceByRoomId(room.getId()) + 1;
        return messageRepository.save(
                RoomMessageEntity.create(
                        "MESSAGE_" + compactUuid(),
                        dispute.getId(),
                        room.getId(),
                        sequence,
                        MessageSenderType.AGENT,
                        JUDGE_SENDER_ROLE,
                        JUDGE_SENDER_ID,
                        sharedCourtAudienceJson(),
                        "[]",
                        MessageType.AGENT_MESSAGE,
                        result.messageText(),
                        "[]",
                        idempotencyKey,
                        roundNo,
                        Instant.now(clock),
                        traceId));
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
            throw new IllegalStateException("cannot serialize hearing court payload", exception);
        }
    }

    private static String participantId(FulfillmentCaseEntity dispute, ActorRole role) {
        return switch (role) {
            case USER -> dispute.getUserId();
            case MERCHANT -> dispute.getMerchantId();
            default -> role.name();
        };
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String judgeRoundOpeningKey(String caseId, int roundNo) {
        return "judge-round-opening:" + caseId + ":" + roundNo;
    }

    private static String judgeRoundTurnKey(String caseId, int roundNo) {
        return "judge-round-turn:" + caseId + ":" + roundNo;
    }

    private static String juryReviewReportKey(String caseId, int roundNo) {
        return "jury-review-report:" + caseId + ":" + roundNo;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record TurnPreparation(
            String caseId,
            int roundNo,
            boolean finalRound,
            String idempotencyKey,
            String lifecycleEventKey,
            HearingCourtAgentCommand command,
            List<String> recoveryReviewFocus,
            boolean complete) {

        private static TurnPreparation completed(
                String caseId,
                int roundNo,
                boolean finalRound,
                String idempotencyKey,
                String lifecycleEventKey) {
            return new TurnPreparation(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey,
                    null,
                    List.of(),
                    true);
        }

        private static TurnPreparation repair(
                String caseId,
                int roundNo,
                boolean finalRound,
                String idempotencyKey,
                String lifecycleEventKey,
                List<String> recoveryReviewFocus) {
            return new TurnPreparation(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey,
                    null,
                    List.copyOf(recoveryReviewFocus),
                    false);
        }

        private static TurnPreparation generate(
                String caseId,
                int roundNo,
                boolean finalRound,
                String idempotencyKey,
                String lifecycleEventKey,
                HearingCourtAgentCommand command) {
            return new TurnPreparation(
                    caseId,
                    roundNo,
                    finalRound,
                    idempotencyKey,
                    lifecycleEventKey,
                    command,
                    List.of(),
                    false);
        }
    }
}
