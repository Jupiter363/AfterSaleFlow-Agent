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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PostCommitSideEffectExecutor postCommit;

    public HearingCourtOrchestrator(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            HearingCourtAgentClient agentClient,
            ObjectMapper objectMapper,
            Clock clock,
            PostCommitSideEffectExecutor postCommit) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.roundRepository = roundRepository;
        this.submissionRepository = submissionRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.postCommit = postCommit;
    }

    public void afterRoundOpenedAfterCommit(String caseId, int roundNo, String traceId) {
        postCommit.execute(
                "hearing-court-round-opened",
                Map.of("case_id", caseId, "round_no", roundNo),
                () -> afterRoundOpened(caseId, roundNo, traceId));
    }

    public void afterRoundClosedAfterCommit(
            String caseId, int roundNo, boolean finalRound, String traceId) {
        postCommit.execute(
                "hearing-court-round-closed",
                Map.of("case_id", caseId, "round_no", roundNo, "final_round", finalRound),
                () -> afterRoundClosed(caseId, roundNo, finalRound, traceId));
    }

    @Transactional
    public void afterRoundOpened(String caseId, int roundNo, String traceId) {
        appendJudgeTurnIfAbsent(
                caseId,
                roundNo,
                false,
                judgeRoundOpeningKey(caseId, roundNo),
                "judge-round-opening-ready:" + caseId + ":" + roundNo,
                traceId);
    }

    @Transactional
    public void afterRoundClosed(
            String caseId, int roundNo, boolean finalRound, String traceId) {
        appendJudgeTurnIfAbsent(
                caseId,
                roundNo,
                finalRound,
                judgeRoundTurnKey(caseId, roundNo),
                "judge-round-turn-ready:" + caseId + ":" + roundNo,
                traceId);
    }

    private void appendJudgeTurnIfAbsent(
            String caseId,
            int roundNo,
            boolean finalRound,
            String idempotencyKey,
            String lifecycleEventKey,
            String traceId) {
        if (messageRepository.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey).isPresent()) {
            return;
        }
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        CaseRoomEntity room =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.HEARING)
                        .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
        HearingRoundEntity round =
                roundRepository
                        .findByCaseIdAndRoundNo(caseId, roundNo)
                        .orElseThrow(() -> new IllegalArgumentException("hearing round not found"));
        List<HearingRoundPartySubmissionEntity> submissions =
                submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        caseId, roundNo);
        HearingCourtAgentCommand command = command(dispute, round, submissions, finalRound);
        HearingCourtAgentResult result = safeGenerate(command, traceId);
        RoomMessageEntity saved =
                appendJudgeMessage(dispute, room, roundNo, result, idempotencyKey, traceId);
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
                result.courtEventType(),
                Map.of(
                        "round_no", result.roundNo(),
                        "next_round_no", result.nextRoundNo() == null ? "" : result.nextRoundNo(),
                        "final_draft_required", result.finalDraftRequired(),
                        "round_summary", result.roundSummary(),
                        "questions_for_user", result.questionsForUser(),
                        "questions_for_merchant", result.questionsForMerchant(),
                        "prompt_version", result.promptVersion(),
                        "model", result.model()),
                lifecycleEventKey,
                JUDGE_SENDER_ID);
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
                    "第 3 轮陈述已封存。AI 法官将基于当前案情、证据和双方陈述形成非最终裁决草案，并提交平台审核员终审。",
                    "模型暂不可用，系统已封存最终轮材料并进入裁决草案生成路径。",
                    List.of(),
                    List.of(),
                    "FINAL_DRAFT_REQUIRED",
                    command.roundNo(),
                    null,
                    true,
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
                "hearing-round-turn-fallback-v1",
                "local-fallback");
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

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
