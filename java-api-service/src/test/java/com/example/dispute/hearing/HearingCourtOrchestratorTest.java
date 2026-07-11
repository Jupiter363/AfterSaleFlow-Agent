package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.HearingCourtAgentClient;
import com.example.dispute.hearing.application.HearingCourtAgentCommand;
import com.example.dispute.hearing.application.HearingCourtAgentResult;
import com.example.dispute.hearing.application.HearingCourtOrchestrator;
import com.example.dispute.hearing.application.ActiveCourtroomContextAssembler;
import com.example.dispute.hearing.application.AgentA2ACommand;
import com.example.dispute.hearing.application.AgentA2AMessageService;
import com.example.dispute.hearing.application.AgentA2AMessageView;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.tool.application.ToolDefinition;
import com.example.dispute.tool.application.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class HearingCourtOrchestratorTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-07T01:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private HearingRoundRepository roundRepository;
    @Mock private HearingRoundPartySubmissionRepository submissionRepository;
    @Mock private HearingRecordRepository hearingRecordRepository;
    @Mock private EvidenceDossierRepository evidenceDossierRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseEventService eventService;
    @Mock private HearingCourtAgentClient agentClient;
    @Mock private AgentA2AMessageService a2aMessageService;
    @Mock private ToolRegistry toolRegistry;
    @Mock private TransactionTemplate courtTransaction;

    private HearingCourtOrchestrator orchestrator;
    private ActiveCourtroomContextAssembler courtroomContextAssembler;

    @BeforeEach
    void setUp() {
        lenient()
                .doAnswer(
                        invocation -> {
                            Consumer<TransactionStatus> action = invocation.getArgument(0);
                            action.accept(mock(TransactionStatus.class));
                            return null;
                        })
                .when(courtTransaction)
                .executeWithoutResult(any());
        lenient()
                .when(courtTransaction.execute(any(TransactionCallback.class)))
                .thenAnswer(
                        invocation -> {
                            TransactionCallback<?> callback = invocation.getArgument(0);
                            return callback.doInTransaction(mock(TransactionStatus.class));
                        });
        lenient()
                .when(caseRepository.findByIdForUpdate(anyString()))
                .thenAnswer(
                        invocation ->
                                caseRepository.findById(invocation.getArgument(0)));
        lenient()
                .when(roomRepository.findByCaseIdAndRoomTypeForUpdate(
                        anyString(), eq(RoomType.HEARING)))
                .thenAnswer(
                        invocation ->
                                roomRepository.findByCaseIdAndRoomType(
                                        invocation.getArgument(0), RoomType.HEARING));
        lenient()
                .when(roundRepository.findByCaseIdAndRoundNoForUpdate(
                        anyString(), anyInt()))
                .thenAnswer(
                        invocation ->
                                roundRepository.findByCaseIdAndRoundNo(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1)));
        ObjectMapper objectMapper = new ObjectMapper();
        lenient()
                .when(a2aMessageService.record(any()))
                .thenAnswer(
                        invocation -> {
                            AgentA2ACommand command = invocation.getArgument(0);
                            return new AgentA2AMessageView(
                                    "A2A_TEST_FORMAL",
                                    command.caseId(),
                                    command.roundNo(),
                                    command.fromAgent(),
                                    command.toAgent(),
                                    command.messageType(),
                                    objectMapper.writeValueAsString(command.inputRefs()),
                                    objectMapper.writeValueAsString(command.payload()),
                                    command.visibility(),
                                    command.agentRunId(),
                                    CLOCK.instant());
                        });
        courtroomContextAssembler =
                new ActiveCourtroomContextAssembler(
                        hearingRecordRepository,
                        evidenceDossierRepository,
                        roundRepository,
                        submissionRepository,
                        a2aMessageService,
                        toolRegistry,
                        objectMapper);
        orchestrator =
                new HearingCourtOrchestrator(
                        caseRepository,
                        roomRepository,
                        roundRepository,
                        submissionRepository,
                        messageRepository,
                        eventService,
                        agentClient,
                        a2aMessageService,
                        courtroomContextAssembler,
                        objectMapper,
                        CLOCK,
                        new PostCommitSideEffectExecutor(Runnable::run),
                        courtTransaction);
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
        when(hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                dispute.getId(),
                                "C0_COURT_BOOTSTRAP",
                                1,
                                "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(Optional.of(bootstrapSnapshot(dispute.getId())));
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
                                "qwen3.7-plus"));

        orchestrator.afterRoundOpened(dispute.getId(), 1, "TRACE_COURT_OPENING_1");

        ArgumentCaptor<HearingCourtAgentCommand> command =
                ArgumentCaptor.forClass(HearingCourtAgentCommand.class);
        verify(agentClient).generateRoundTurn(command.capture(), eq("TRACE_COURT_OPENING_1"), any());
        assertThat(command.getValue().roundStatus()).isEqualTo("OPEN");
        assertThat(command.getValue().partySubmissions()).isEmpty();
        assertThat(command.getValue().courtroomContextJson())
                .contains("fact_evidence_matrix")
                .contains("物流显示已签收");

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
    void invokesTheRemoteCourtAgentOutsideAnyActiveCourtTransaction() {
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
        when(hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                dispute.getId(),
                                "C0_COURT_BOOTSTRAP",
                                1,
                                "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(Optional.of(bootstrapSnapshot(dispute.getId())));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "judge-round-opening:" + dispute.getId() + ":1"))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(agentClient.generateRoundTurn(any(), eq("TRACE_COURT_OPENING_1"), any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(TransactionSynchronizationManager
                                            .isActualTransactionActive())
                                    .isFalse();
                            return openingResult();
                        });
        HearingCourtOrchestrator transactionAwareOrchestrator =
                new HearingCourtOrchestrator(
                        caseRepository,
                        roomRepository,
                        roundRepository,
                        submissionRepository,
                        messageRepository,
                        eventService,
                        agentClient,
                        a2aMessageService,
                        courtroomContextAssembler,
                        new ObjectMapper(),
                        CLOCK,
                        new PostCommitSideEffectExecutor(Runnable::run),
                        new TransactionTemplate(new TrackingTransactionManager()));

        transactionAwareOrchestrator.afterRoundOpened(
                dispute.getId(), 1, "TRACE_COURT_OPENING_1");
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
        when(hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                dispute.getId(),
                                "C0_COURT_BOOTSTRAP",
                                1,
                                "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(Optional.of(bootstrapSnapshot(dispute.getId())));
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
                                "qwen3.7-plus"));
        when(toolRegistry.definitions())
                .thenReturn(
                        List.of(
                                new ToolDefinition(
                                        "REFUND",
                                        "after_sale_tool",
                                        "refund",
                                        "模拟退款",
                                        "仅在平台审核通过后模拟退款动作，不直接调用真实支付下游。",
                                        RiskLevel.HIGH,
                                        true,
                                        true)));

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
        assertThat(command.getValue().courtroomContextJson())
                .contains("intake_dossier")
                .contains("evidence_dossier")
                .contains("fact_evidence_matrix")
                .contains("execution_tool_declarations")
                .contains("\"action_type\":\"REFUND\"")
                .contains("\"tool_name\":\"after_sale_tool\"")
                .contains("\"requires_approved_plan\":true");

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

    @Test
    void afterRoundClosedComposesJudgeContextFromActiveEvidenceDossierVersion() {
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
                        "HEARING_ROUND_3",
                        dispute.getId(),
                        null,
                        3,
                        1,
                        Instant.parse("2026-07-07T01:05:00Z"),
                        Instant.parse("2026-07-07T01:00:00Z"),
                        "system");
        round.complete(
                "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
                null,
                Instant.parse("2026-07-07T01:04:00Z"),
                "hearing-controller");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(roundRepository.findByCaseIdAndRoundNo(dispute.getId(), 3))
                .thenReturn(Optional.of(round));
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        dispute.getId(), 3))
                .thenReturn(List.of());
        when(hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                dispute.getId(),
                                "C0_COURT_BOOTSTRAP",
                                1,
                                "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(Optional.of(bootstrapSnapshot(dispute.getId())));
        when(evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(dispute.getId()))
                .thenReturn(Optional.of(activeEvidenceDossierV2(dispute.getId())));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "judge-round-turn:" + dispute.getId() + ":3"))
                .thenReturn(Optional.empty());
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "jury-review-report:" + dispute.getId() + ":3"))
                .thenReturn(Optional.empty(), Optional.of(mock(RoomMessageEntity.class)));
        when(a2aMessageService.hasFormalJuryReviewReport(dispute.getId(), 3))
                .thenReturn(true);
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(8L, 9L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(agentClient.generateRoundTurn(any(), eq("TRACE_COURT_ROUND_3"), any()))
                .thenReturn(
                        new HearingCourtAgentResult(
                                "JUDGE",
                                "第 3 轮方案确认已封存，AI 法官将生成裁决草案。",
                                "第三轮双方已完成方案确认或异议说明。",
                                List.of(),
                                List.of(),
                                "FINAL_DRAFT_REQUIRED",
                                3,
                                null,
                                false,
                                List.of("用户认可退款方向，但要求复核签收人身份是否已核验清楚。"),
                                "hearing-round-turn-v1",
                                "qwen3.7-plus"));
        when(a2aMessageService.findForJudge(dispute.getId(), 3))
                .thenReturn(
                        List.of(
                                new AgentA2AMessageView(
                                        "A2A_JURY_2",
                                        dispute.getId(),
                                        2,
                                        "JURY_PANEL",
                                        "PRESIDING_JUDGE",
                                        "JURY_SILENT_NOTE",
                                        "{\"evidence_dossier_version\":2}",
                                        "{\"judge_attention\":[\"签收人身份仍需关注\"]}",
                                        "SYSTEM_AUDIT_ONLY",
                                        "RUN_JURY_2",
                                        Instant.parse("2026-07-07T01:04:30Z"))));

        Runnable completion = mock(Runnable.class);
        orchestrator.afterRoundClosedAfterCommit(
                dispute.getId(), 3, true, "TRACE_COURT_ROUND_3", completion);

        ArgumentCaptor<HearingCourtAgentCommand> command =
                ArgumentCaptor.forClass(HearingCourtAgentCommand.class);
        verify(agentClient).generateRoundTurn(command.capture(), eq("TRACE_COURT_ROUND_3"), any());
        assertThat(command.getValue().courtroomContextJson())
                .contains("\"baseline_version\":1")
                .contains("\"active_version\":2")
                .contains("\"dossier_version\":2")
                .contains("第 2 轮证据解释后更新的签收证明矩阵")
                .contains("\"jury_a2a_notes\"")
                .contains("签收人身份仍需关注")
                .doesNotContain("\"dossier_version\":1,\"dossier_status\":\"FROZEN\"");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> lifecyclePayload =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(eventService)
                .recordLifecycleEvent(
                        eq(dispute.getId()),
                        eq(room.getId()),
                        eq("FINAL_DRAFT_REQUIRED"),
                        lifecyclePayload.capture(),
                        eq("judge-round-turn-ready:" + dispute.getId() + ":3"),
                        eq("presiding-judge"));
        assertThat(lifecyclePayload.getValue())
                .containsEntry(
                        "review_focus_signal",
                        List.of("用户认可退款方向，但要求复核签收人身份是否已核验清楚。"));

        ArgumentCaptor<AgentA2ACommand> a2aCommand =
                ArgumentCaptor.forClass(AgentA2ACommand.class);
        verify(a2aMessageService).record(a2aCommand.capture());
        assertThat(a2aCommand.getValue().roundNo()).isEqualTo(3);
        assertThat(a2aCommand.getValue().fromAgent()).isEqualTo("JURY_PANEL");
        assertThat(a2aCommand.getValue().toAgent()).isEqualTo("PRESIDING_JUDGE");
        assertThat(a2aCommand.getValue().messageType()).isEqualTo("JURY_REVIEW_REPORT");
        assertThat(a2aCommand.getValue().visibility()).isEqualTo("REVIEWER_VISIBLE");
        assertThat(a2aCommand.getValue().inputRefs())
                .containsEntry(
                        "review_focus_signal",
                        List.of("用户认可退款方向，但要求复核签收人身份是否已核验清楚。"));
        assertThat(a2aCommand.getValue().payload())
                .containsEntry("risk_level", "MEDIUM")
                .containsEntry("confidence_score", 75);
        assertThat(a2aCommand.getValue().payload().get("summary").toString())
                .contains("评审团已完成第三轮复核");

        ArgumentCaptor<RoomMessageEntity> savedMessages =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository, times(2)).save(savedMessages.capture());
        RoomMessageEntity juryMessage = savedMessages.getAllValues().get(1);
        assertThat(juryMessage.getSenderRole()).isEqualTo("JURY");
        assertThat(juryMessage.getSenderId()).isEqualTo("jury-panel");
        assertThat(juryMessage.getMessageType()).isEqualTo(MessageType.JURY_REVIEW_REPORT);
        assertThat(juryMessage.getMessageText())
                .contains("评审团已完成第三轮复核")
                .contains("review_focus_signal")
                .doesNotContain("a2a_message_id");
        InOrder completionOrder = inOrder(a2aMessageService, completion);
        completionOrder.verify(a2aMessageService).record(any());
        completionOrder.verify(completion).run();
    }

    @Test
    void finalRoundRetryRepairsMissingFormalJuryReportBeforeCompletion() {
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
                        "HEARING_ROUND_3",
                        dispute.getId(),
                        null,
                        3,
                        2,
                        Instant.parse("2026-07-07T01:05:00Z"),
                        Instant.parse("2026-07-07T01:00:00Z"),
                        "system");
        round.complete(
                "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
                null,
                Instant.parse("2026-07-07T01:04:00Z"),
                "hearing-controller");
        RoomMessageEntity existingJudge =
                RoomMessageEntity.create(
                        "MESSAGE_EXISTING_JUDGE_3",
                        dispute.getId(),
                        room.getId(),
                        9,
                        MessageSenderType.AGENT,
                        "JUDGE",
                        "presiding-judge",
                        "[\"USER\",\"MERCHANT\"]",
                        "[]",
                        MessageType.AGENT_MESSAGE,
                        "第三轮法官收束已写入。",
                        "[]",
                        "judge-round-turn:" + dispute.getId() + ":3",
                        3,
                        Instant.parse("2026-07-07T01:04:30Z"),
                        "TRACE_COURT_ROUND_3");
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "judge-round-turn:" + dispute.getId() + ":3"))
                .thenReturn(Optional.of(existingJudge));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "jury-review-report:" + dispute.getId() + ":3"))
                .thenReturn(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(mock(RoomMessageEntity.class)));
        when(a2aMessageService.hasFormalJuryReviewReport(dispute.getId(), 3))
                .thenReturn(true);
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(roundRepository.findByCaseIdAndRoundNo(dispute.getId(), 3))
                .thenReturn(Optional.of(round));
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        dispute.getId(), 3))
                .thenReturn(List.of());
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Runnable completion = mock(Runnable.class);

        orchestrator.afterRoundClosedAfterCommit(
                dispute.getId(), 3, true, "TRACE_COURT_ROUND_3", completion);

        ArgumentCaptor<RoomMessageEntity> recovered =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(recovered.capture());
        assertThat(recovered.getValue().getMessageType())
                .isEqualTo(MessageType.JURY_REVIEW_REPORT);
        InOrder completionOrder = inOrder(a2aMessageService, messageRepository, completion);
        completionOrder.verify(a2aMessageService).record(any());
        completionOrder.verify(messageRepository).save(any());
        completionOrder.verify(completion).run();
        verifyNoInteractions(agentClient);
    }

    @Test
    void finalRoundRetryRepairsMissingA2AWhenTheJuryRoomMessageAlreadyExists()
            throws Exception {
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
                        "HEARING_ROUND_3",
                        dispute.getId(),
                        null,
                        3,
                        2,
                        Instant.parse("2026-07-07T01:05:00Z"),
                        Instant.parse("2026-07-07T01:00:00Z"),
                        "system");
        round.complete(
                "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
                null,
                Instant.parse("2026-07-07T01:04:00Z"),
                "hearing-controller");
        RoomMessageEntity existingJudge =
                RoomMessageEntity.create(
                        "MESSAGE_EXISTING_JUDGE_3",
                        dispute.getId(),
                        room.getId(),
                        9,
                        MessageSenderType.AGENT,
                        "JUDGE",
                        "presiding-judge",
                        "[\"USER\",\"MERCHANT\"]",
                        "[]",
                        MessageType.AGENT_MESSAGE,
                        "第三轮法官收束已经写入。",
                        "[]",
                        "judge-round-turn:" + dispute.getId() + ":3",
                        3,
                        Instant.parse("2026-07-07T01:04:30Z"),
                        "TRACE_COURT_ROUND_3");
        RoomMessageEntity existingJuryReport =
                RoomMessageEntity.create(
                        "MESSAGE_EXISTING_JURY_3",
                        dispute.getId(),
                        room.getId(),
                        10,
                        MessageSenderType.AGENT,
                        "JURY",
                        "jury-panel",
                        "[\"USER\",\"MERCHANT\"]",
                        "[]",
                        MessageType.JURY_REVIEW_REPORT,
                        "{\"summary\":\"existing report\"}",
                        "[]",
                        "jury-review-report:" + dispute.getId() + ":3",
                        3,
                        Instant.parse("2026-07-07T01:04:31Z"),
                        "TRACE_COURT_ROUND_3");
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "judge-round-turn:" + dispute.getId() + ":3"))
                .thenReturn(Optional.of(existingJudge));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "jury-review-report:" + dispute.getId() + ":3"))
                .thenReturn(Optional.of(existingJuryReport));
        when(a2aMessageService.hasFormalJuryReviewReport(dispute.getId(), 3))
                .thenReturn(false, true);
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(roundRepository.findByCaseIdAndRoundNo(dispute.getId(), 3))
                .thenReturn(Optional.of(round));
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        dispute.getId(), 3))
                .thenReturn(List.of());
        Runnable completion = mock(Runnable.class);

        orchestrator.afterRoundClosedAfterCommit(
                dispute.getId(), 3, true, "TRACE_COURT_ROUND_3", completion);

        ArgumentCaptor<AgentA2ACommand> repairedA2A =
                ArgumentCaptor.forClass(AgentA2ACommand.class);
        verify(a2aMessageService).record(repairedA2A.capture());
        JsonNode repairedPayload =
                new ObjectMapper().valueToTree(repairedA2A.getValue().payload());
        assertThat(repairedPayload)
                .isEqualTo(new ObjectMapper().readTree(existingJuryReport.getMessageText()));
        verify(messageRepository, never()).save(any());
        verify(completion).run();
        verifyNoInteractions(agentClient);
    }

    @Test
    void finalRoundRetryReusesTheSurvivingA2APayloadWhenRepairingTheRoomCard()
            throws Exception {
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
                        "HEARING_ROUND_3",
                        dispute.getId(),
                        null,
                        3,
                        2,
                        Instant.parse("2026-07-07T01:05:00Z"),
                        Instant.parse("2026-07-07T01:00:00Z"),
                        "system");
        round.complete(
                "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
                null,
                Instant.parse("2026-07-07T01:04:00Z"),
                "hearing-controller");
        RoomMessageEntity existingJudge =
                RoomMessageEntity.create(
                        "MESSAGE_EXISTING_JUDGE_3",
                        dispute.getId(),
                        room.getId(),
                        9,
                        MessageSenderType.AGENT,
                        "JUDGE",
                        "presiding-judge",
                        "[\"USER\",\"MERCHANT\"]",
                        "[]",
                        MessageType.AGENT_MESSAGE,
                        "第三轮法官收束已经写入。",
                        "[]",
                        "judge-round-turn:" + dispute.getId() + ":3",
                        3,
                        Instant.parse("2026-07-07T01:04:30Z"),
                        "TRACE_COURT_ROUND_3");
        AgentA2AMessageView survivingA2A =
                new AgentA2AMessageView(
                        "A2A_EXISTING_JURY_3",
                        dispute.getId(),
                        3,
                        "JURY_PANEL",
                        "PRESIDING_JUDGE",
                        "JURY_REVIEW_REPORT",
                        "{\"source\":\"surviving-a2a\",\"round_no\":3}",
                        "{\"summary\":\"必须复用的正式复核结论\",\"confidence_score\":91}",
                        "REVIEWER_VISIBLE",
                        "RUN_JURY_3",
                        Instant.parse("2026-07-07T01:04:31Z"));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "judge-round-turn:" + dispute.getId() + ":3"))
                .thenReturn(Optional.of(existingJudge));
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "jury-review-report:" + dispute.getId() + ":3"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(mock()));
        when(a2aMessageService.hasFormalJuryReviewReport(dispute.getId(), 3))
                .thenReturn(true, true);
        when(a2aMessageService.findFormalJuryReviewReport(dispute.getId(), 3))
                .thenReturn(Optional.of(survivingA2A));
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(roundRepository.findByCaseIdAndRoundNo(dispute.getId(), 3))
                .thenReturn(Optional.of(round));
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        dispute.getId(), 3))
                .thenReturn(List.of());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(12L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        orchestrator.afterRoundClosed(
                dispute.getId(), 3, true, "TRACE_COURT_ROUND_3");

        ArgumentCaptor<RoomMessageEntity> repairedRoomCard =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(repairedRoomCard.capture());
        assertThat(repairedRoomCard.getValue().getSequenceNo()).isEqualTo(13);
        assertThat(new ObjectMapper().readTree(repairedRoomCard.getValue().getMessageText()))
                .isEqualTo(new ObjectMapper().readTree(survivingA2A.payloadJson()));
        verify(a2aMessageService, never()).record(any());
        verifyNoInteractions(agentClient);
    }

    @Test
    void finalConvergenceContextRequiresFormalJuryReport() {
        FulfillmentCaseEntity dispute = hearingCase();
        when(hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                dispute.getId(),
                                "C0_COURT_BOOTSTRAP",
                                1,
                                "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(Optional.of(bootstrapSnapshot(dispute.getId())));
        when(evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(dispute.getId()))
                .thenReturn(Optional.of(activeEvidenceDossierV2(dispute.getId())));
        when(a2aMessageService.findForJudge(dispute.getId(), 3)).thenReturn(List.of());

        assertThatThrownBy(
                        () ->
                                courtroomContextAssembler.assembleFinalConvergence(
                                        dispute.getId(), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("formal jury review report");
    }

    @Test
    void afterRoundClosedRequiresFrozenCourtroomContext() {
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
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(roundRepository.findByCaseIdAndRoundNo(dispute.getId(), 1))
                .thenReturn(Optional.of(round));
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        dispute.getId(), 1))
                .thenReturn(List.of());
        when(hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                dispute.getId(),
                                "C0_COURT_BOOTSTRAP",
                                1,
                                "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(Optional.empty());
        when(messageRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "judge-round-turn:" + dispute.getId() + ":1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                orchestrator.afterRoundClosed(
                                        dispute.getId(), 1, false, "TRACE_COURT_ROUND_1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hearing bootstrap snapshot not found");
        verifyNoInteractions(agentClient);
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

    private static HearingRecordEntity bootstrapSnapshot(String caseId) {
        return HearingRecordEntity.record(
                "HREC_BOOTSTRAP",
                caseId,
                "HEARING_STATE_BOOTSTRAP",
                "hearing-window-" + caseId,
                "C0_COURT_BOOTSTRAP",
                1,
                "BOOTSTRAP_DOSSIER_SNAPSHOT",
                "{\"source\":\"test\"}",
                """
                {
                  "schema_version": "hearing_bootstrap_dossier.v1",
                  "evidence_dossier_version": 1,
                  "source_versions": {
                    "evidence_dossier_version": 1
                  },
                  "intake_dossier": {
                    "case_story": "用户称物流显示已签收但本人未收到包裹。"
                  },
                  "evidence_dossier": {
                    "dossier_version": 1,
                    "dossier_status": "FROZEN",
                    "fact_evidence_matrix": [
                      {
                        "fact_id": "FACT_SIGNED",
                        "fact": "物流显示已签收",
                        "supporting_evidence": ["EVIDENCE_LOGISTICS"],
                        "evidence_strength": "MEDIUM"
                      }
                    ]
                  }
                }
                """,
                "{}",
                "hearing-bootstrap-v1",
                "java-deterministic-bootstrap",
                null,
                null,
                "hearing-bootstrap");
    }

    private static EvidenceDossierEntity activeEvidenceDossierV2(String caseId) {
        return EvidenceDossierEntity.frozen(
                "EVIDENCE_DOSSIER_ACTIVE_V2",
                caseId,
                2,
                "evidence-clerk",
                """
                {
                  "overall_confidence_score": 76,
                  "handoff_notes": "第 2 轮证据解释后更新的签收证明矩阵"
                }
                """,
                "[]",
                """
                {
                  "updated_after_round": 2,
                  "supersedes_version": 1,
                  "active_version": 2,
                  "fact_evidence_matrix": [
                    {
                      "fact_id": "FACT_SIGNED",
                      "fact": "第 2 轮证据解释后更新的签收证明矩阵",
                      "supporting_evidence": ["EVIDENCE_LOGISTICS_V2"],
                      "evidence_strength": "MEDIUM"
                    }
                  ]
                }
                """);
    }

    private static HearingCourtAgentResult openingResult() {
        return new HearingCourtAgentResult(
                "JUDGE",
                "小法庭现在开庭。",
                "法官已打开第一轮事实陈述。",
                List.of("请用户说明争议事实。"),
                List.of("请商家说明履约记录。"),
                "JUDGE_OPENING_READY",
                1,
                1,
                false,
                "hearing-round-opening-v1",
                "test-model");
    }

    private static final class TrackingTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
