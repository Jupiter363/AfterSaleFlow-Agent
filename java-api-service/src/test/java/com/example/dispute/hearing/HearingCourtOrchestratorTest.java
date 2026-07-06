package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.HearingCourtAgentClient;
import com.example.dispute.hearing.application.HearingCourtAgentCommand;
import com.example.dispute.hearing.application.HearingCourtAgentResult;
import com.example.dispute.hearing.application.HearingCourtOrchestrator;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HearingCourtOrchestratorTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-07T01:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private HearingRoundRepository roundRepository;
    @Mock private HearingRoundPartySubmissionRepository submissionRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseEventService eventService;
    @Mock private HearingCourtAgentClient agentClient;

    private HearingCourtOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator =
                new HearingCourtOrchestrator(
                        caseRepository,
                        roomRepository,
                        roundRepository,
                        submissionRepository,
                        messageRepository,
                        eventService,
                        agentClient,
                        new ObjectMapper(),
                        CLOCK,
                        new PostCommitSideEffectExecutor(Runnable::run));
    }

    @Test
    void afterCommitRoundTurnFailuresDoNotPropagateToTheBusinessRequest() {
        assertThatCode(
                        () ->
                                orchestrator.afterRoundClosedAfterCommit(
                                        "CASE_COURT", 1, false, "TRACE_COURT_ROUND_1"))
                .doesNotThrowAnyException();
    }

    @Test
    void afterRoundOpenedAppendsOpeningJudgeMessage() {
        FulfillmentCaseEntity dispute = hearingCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_HEARING_CASE_COURT",
                        dispute.getId(),
                        RoomType.HEARING,
                        OffsetDateTime.parse("2026-07-07T01:00:00Z"),
                        "system");
        HearingRoundEntity round =
                HearingRoundEntity.open(
                        "HEARING_ROUND_1",
                        dispute.getId(),
                        null,
                        1,
                        2,
                        Instant.parse("2026-07-07T01:05:00Z"),
                        Instant.parse("2026-07-07T01:00:00Z"),
                        "system");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(roundRepository.findByCaseIdAndRoundNo(dispute.getId(), 1))
                .thenReturn(Optional.of(round));
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        dispute.getId(), 1))
                .thenReturn(List.of());
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "judge-round-opening:" + dispute.getId() + ":1"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(agentClient.generateRoundTurn(any(), eq("TRACE_COURT_OPENING_1"), any()))
                .thenReturn(
                        new HearingCourtAgentResult(
                                "JUDGE",
                                "小法庭现在开庭。第 1 轮请双方先围绕接待室卷宗和证据室材料说明关键事实。",
                                "法官已打开第 1 轮事实陈述。",
                                List.of("请用户说明争议发生经过。"),
                                List.of("请商家说明履约记录。"),
                                "JUDGE_OPENING_READY",
                                1,
                                1,
                                false,
                                "hearing-round-opening-v1",
                                "deepseek-v4-flash"));

        orchestrator.afterRoundOpened(dispute.getId(), 1, "TRACE_COURT_OPENING_1");

        ArgumentCaptor<HearingCourtAgentCommand> command =
                ArgumentCaptor.forClass(HearingCourtAgentCommand.class);
        verify(agentClient).generateRoundTurn(command.capture(), eq("TRACE_COURT_OPENING_1"), any());
        assertThat(command.getValue().roundStatus()).isEqualTo("OPEN");
        assertThat(command.getValue().partySubmissions()).isEmpty();

        ArgumentCaptor<RoomMessageEntity> savedMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(savedMessage.capture());
        assertThat(savedMessage.getValue().getSequenceNo()).isEqualTo(1);
        assertThat(savedMessage.getValue().getSenderRole()).isEqualTo("JUDGE");
        assertThat(savedMessage.getValue().getHearingRound()).isEqualTo(1);
        assertThat(savedMessage.getValue().getMessageText()).contains("小法庭现在开庭");
        verify(eventService)
                .recordLifecycleEvent(
                        eq(dispute.getId()),
                        eq(room.getId()),
                        eq("JUDGE_OPENING_READY"),
                        any(),
                        eq("judge-round-opening-ready:" + dispute.getId() + ":1"),
                        eq("presiding-judge"));
    }

    @Test
    void afterRoundClosedAppendsOneIdempotentJudgeMessageAndLifecycleEvent() {
        FulfillmentCaseEntity dispute = hearingCase();
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_HEARING_CASE_COURT",
                        dispute.getId(),
                        RoomType.HEARING,
                        OffsetDateTime.parse("2026-07-07T01:00:00Z"),
                        "system");
        HearingRoundEntity round =
                HearingRoundEntity.open(
                        "HEARING_ROUND_1",
                        dispute.getId(),
                        null,
                        1,
                        2,
                        Instant.parse("2026-07-07T01:05:00Z"),
                        Instant.parse("2026-07-07T01:00:00Z"),
                        "system");
        round.complete(
                "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
                null,
                Instant.parse("2026-07-07T01:04:00Z"),
                "hearing-controller");
        var userSubmission =
                HearingRoundPartySubmissionEntity.submit(
                        "SUB_USER_1",
                        dispute.getId(),
                        round.getId(),
                        1,
                        ActorRole.USER,
                        dispute.getUserId(),
                        HearingRoundSubmissionSource.PARTY_ACTION,
                        "{\"statement\":\"用户称物流显示签收但本人未收到包裹。\"}",
                        Instant.parse("2026-07-07T01:02:00Z"));
        var merchantSubmission =
                HearingRoundPartySubmissionEntity.submit(
                        "SUB_MERCHANT_1",
                        dispute.getId(),
                        round.getId(),
                        1,
                        ActorRole.MERCHANT,
                        dispute.getMerchantId(),
                        HearingRoundSubmissionSource.PARTY_ACTION,
                        "{\"statement\":\"商家称已按地址发货并有物流签收记录。\"}",
                        Instant.parse("2026-07-07T01:03:00Z"));
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(roundRepository.findByCaseIdAndRoundNo(dispute.getId(), 1))
                .thenReturn(Optional.of(round));
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        dispute.getId(), 1))
                .thenReturn(List.of(userSubmission, merchantSubmission));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "judge-round-turn:" + dispute.getId() + ":1"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(6L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(agentClient.generateRoundTurn(any(), eq("TRACE_COURT_ROUND_1"), any()))
                .thenReturn(
                        new HearingCourtAgentResult(
                                "JUDGE",
                                "第一轮事实陈述已封存。下一轮请用户说明签收现场情况，请商家说明物流交接记录。",
                                "本轮双方围绕签收未收到展开事实陈述。",
                                List.of("请补充签收现场、快递柜或驿站沟通记录。"),
                                List.of("请补充物流交接、签收凭证和发货履约记录。"),
                                "JUDGE_NEXT_QUESTIONS_READY",
                                1,
                                2,
                                false,
                                "hearing-round-turn-v1",
                                "deepseek-v4-flash"));

        orchestrator.afterRoundClosed(dispute.getId(), 1, false, "TRACE_COURT_ROUND_1");

        ArgumentCaptor<HearingCourtAgentCommand> command =
                ArgumentCaptor.forClass(HearingCourtAgentCommand.class);
        verify(agentClient).generateRoundTurn(command.capture(), eq("TRACE_COURT_ROUND_1"), any());
        assertThat(command.getValue().caseId()).isEqualTo(dispute.getId());
        assertThat(command.getValue().roundNo()).isEqualTo(1);
        assertThat(command.getValue().finalRound()).isFalse();
        assertThat(command.getValue().partySubmissions())
                .extracting(HearingCourtAgentCommand.PartySubmission::participantRole)
                .containsExactly("USER", "MERCHANT");

        ArgumentCaptor<RoomMessageEntity> savedMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(savedMessage.capture());
        assertThat(savedMessage.getValue().getSequenceNo()).isEqualTo(7);
        assertThat(savedMessage.getValue().getSenderRole()).isEqualTo("JUDGE");
        assertThat(savedMessage.getValue().getSenderId()).isEqualTo("presiding-judge");
        assertThat(savedMessage.getValue().getMessageType()).isEqualTo(MessageType.AGENT_MESSAGE);
        assertThat(savedMessage.getValue().getHearingRound()).isEqualTo(1);
        assertThat(savedMessage.getValue().getMessageText()).contains("第一轮事实陈述已封存");

        verify(eventService)
                .recordRoomMessage(
                        eq(dispute.getId()),
                        eq(room.getId()),
                        eq(savedMessage.getValue().getId()),
                        eq(savedMessage.getValue().getMessageText()),
                        eq(savedMessage.getValue().getAudienceJson()),
                        eq(savedMessage.getValue().getAudienceActorIdsJson()),
                        eq("presiding-judge"));
        verify(eventService)
                .recordLifecycleEvent(
                        eq(dispute.getId()),
                        eq(room.getId()),
                        eq("JUDGE_NEXT_QUESTIONS_READY"),
                        any(),
                        eq("judge-round-turn-ready:" + dispute.getId() + ":1"),
                        eq("presiding-judge"));
        assertThat(savedMessage.getValue().getAudienceJson())
                .contains("USER")
                .contains("MERCHANT")
                .contains("PLATFORM_REVIEWER");
        assertThat(savedMessage.getValue().getAudienceActorIdsJson()).isEqualTo("[]");
    }

    private static FulfillmentCaseEntity hearingCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_COURT",
                "ORDER-COURT",
                "AS-COURT",
                "LOG-COURT",
                "user-local",
                "merchant-local",
                "idem-court",
                "SIGNED_NOT_RECEIVED",
                "物流显示签收但用户称未收到",
                "用户称物流显示已签收但本人未收到包裹，商家称已正常发货并有签收记录。",
                RiskLevel.HIGH,
                CaseStatus.HEARING_OPEN,
                "HEARING",
                OffsetDateTime.parse("2026-07-07T04:00:00Z"),
                "OMS",
                "EXT-COURT",
                "external-adapter");
    }
}
