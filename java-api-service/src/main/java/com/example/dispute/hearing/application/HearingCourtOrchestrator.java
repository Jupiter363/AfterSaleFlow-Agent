package com.example.dispute.hearing.application;

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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    public HearingCourtOrchestrator(
            FulfillmentCaseRepository caseRepository,
            CaseRoomRepository roomRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            RoomMessageRepository messageRepository,
            CaseEventService eventService,
            HearingCourtAgentClient agentClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.roomRepository = roomRepository;
        this.roundRepository = roundRepository;
        this.submissionRepository = submissionRepository;
        this.messageRepository = messageRepository;
        this.eventService = eventService;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void afterRoundClosedAfterCommit(
            String caseId, int roundNo, boolean finalRound, String traceId) {
        Runnable action = () -> afterRoundClosed(caseId, roundNo, finalRound, traceId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    @Transactional
    public void afterRoundClosed(
            String caseId, int roundNo, boolean finalRound, String traceId) {
        String idempotencyKey = judgeRoundTurnKey(caseId, roundNo);
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
        HearingCourtAgentCommand command =
                command(dispute, round, submissions, finalRound);
        HearingCourtAgentResult result = safeGenerate(command, traceId);
        RoomMessageEntity saved = appendJudgeMessage(dispute, room, roundNo, result, idempotencyKey, traceId);
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
                "judge-round-turn-ready:" + dispute.getId() + ":" + roundNo,
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
        boolean finalRound = command.finalRound();
        String message =
                finalRound
                        ? "第三轮陈述已封存。AI 法官将基于当前案情、证据和双方陈述形成非最终裁决草案，并提交平台审核员终审。"
                        : "本轮庭审陈述已封存。下一轮将继续围绕争议焦点进行定向说明，双方可分别补充与本案事实和证据相关的陈述。";
        return new HearingCourtAgentResult(
                JUDGE_SENDER_ROLE,
                message,
                "模型暂不可用，系统已封存本轮材料并按结构化庭审流程继续。",
                finalRound ? List.of() : List.of("请围绕法官上一轮问题补充客观事实、证据来源和时间线。"),
                finalRound ? List.of() : List.of("请围绕履约记录、证据来源和与用户主张的差异补充说明。"),
                finalRound ? "FINAL_DRAFT_REQUIRED" : "JUDGE_NEXT_QUESTIONS_READY",
                command.roundNo(),
                finalRound ? null : command.roundNo() + 1,
                finalRound,
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

    private static String judgeRoundTurnKey(String caseId, int roundNo) {
        return "judge-round-turn:" + caseId + ":" + roundNo;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
