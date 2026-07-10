package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;

import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.evidence.application.EvidenceDossierRevisionService;
import com.example.dispute.hearing.application.CompleteHearingRoundCommand;
import com.example.dispute.hearing.application.AgentA2ACommand;
import com.example.dispute.hearing.application.AgentA2AMessageService;
import com.example.dispute.hearing.application.HearingCourtOrchestrator;
import com.example.dispute.hearing.application.HearingFinalDraftService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import com.example.dispute.hearing.application.HearingStatusView;
import com.example.dispute.hearing.application.SettlementProposalCommand;
import com.example.dispute.hearing.application.SettlementService;
import com.example.dispute.hearing.application.SettlementVersionConflictException;
import com.example.dispute.hearing.application.SubmitHearingRoundCommand;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.hearing.domain.HearingStopReason;
import com.example.dispute.hearing.domain.SettlementStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementConfirmationRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementProposalRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.workflow.application.HearingAgentClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnableConfigurationProperties(DisputeProperties.class)
@Import({
    SettlementService.class,
    HearingRoundService.class,
    HearingFinalDraftService.class,
    AgentA2AMessageService.class,
    EvidenceDossierRevisionService.class,
    NotificationService.class,
    CaseEventService.class,
    HearingCollaborationIntegrationTest.FixedClockConfiguration.class
})
@Testcontainers
class HearingCollaborationIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_hearing")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_hearing");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private SettlementService settlementService;
    @Autowired private HearingRoundService roundService;
    @Autowired private AgentA2AMessageService a2aMessageService;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private CaseRoomRepository roomRepository;
    @Autowired private SettlementProposalRepository proposalRepository;
    @Autowired private SettlementConfirmationRepository confirmationRepository;
    @Autowired private HearingRoundRepository roundRepository;
    @Autowired private HearingRoundPartySubmissionRepository submissionRepository;
    @Autowired private CaseTimelineEventRepository eventRepository;
    @Autowired private HearingStateRepository hearingStateRepository;
    @Autowired private AdjudicationDraftRepository draftRepository;
    @Autowired private EvidenceDossierRepository evidenceDossierRepository;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private RemedyPlanRepository remedyPlanRepository;
    @Autowired private ReviewPacketRepository reviewPacketRepository;
    @Autowired private ReviewTaskRepository reviewTaskRepository;
    @Autowired private MutableClock mutableClock;
    @MockitoBean private HearingWorkflowCoordinator hearingWorkflowCoordinator;
    @MockitoBean private HearingCourtOrchestrator hearingCourtOrchestrator;
    @MockitoBean private HearingAgentClient hearingAgentClient;
    @MockitoBean private AccessSessionResolver accessSessionResolver;
    @MockitoBean private SessionPermissionService sessionPermissionService;

    private AuthenticatedActor user;
    private AuthenticatedActor merchant;
    private AuthenticatedActor system;

    @BeforeEach
    void setUp() {
        user = new AuthenticatedActor("user-local", ActorRole.USER);
        merchant = new AuthenticatedActor("merchant-local", ActorRole.MERCHANT);
        system = new AuthenticatedActor("hearing-controller", ActorRole.SYSTEM);
        mutableClock.set(Instant.parse("2026-07-03T01:00:00Z"));
    }

    @Test
    void onlyBothConfirmationsOnTheCurrentSettlementVersionConverge() {
        seedHearing("CASE_SETTLEMENT");

        var v1 =
                settlementService.propose(
                        "CASE_SETTLEMENT",
                        new SettlementProposalCommand("退款 50 元", "{\"refund\":50}"),
                        merchant,
                        "TRACE_v1");
        settlementService.confirm(
                "CASE_SETTLEMENT", v1.version(), user, "confirm-v1-user");
        var v2 =
                settlementService.propose(
                        "CASE_SETTLEMENT",
                        new SettlementProposalCommand("退款 80 元", "{\"refund\":80}"),
                        merchant,
                        "TRACE_v2");

        assertThat(
                        settlementService
                                .get("CASE_SETTLEMENT", v1.version(), user)
                                .status())
                .isEqualTo(SettlementStatus.SUPERSEDED);
        assertThatThrownBy(
                        () ->
                                settlementService.confirm(
                                        "CASE_SETTLEMENT",
                                        v1.version(),
                                        merchant,
                                        "confirm-v1-merchant"))
                .isInstanceOf(SettlementVersionConflictException.class);
        assertThatThrownBy(
                        () ->
                                settlementService.confirm(
                                        "CASE_SETTLEMENT",
                                        v2.version(),
                                        user,
                                        "confirm-v1-user"))
                .isInstanceOf(IdempotencyConflictException.class);

        settlementService.confirm(
                "CASE_SETTLEMENT", v2.version(), user, "confirm-v2-user");
        var confirmed =
                settlementService.confirm(
                        "CASE_SETTLEMENT",
                        v2.version(),
                        merchant,
                        "confirm-v2-merchant");
        var replayedFinalConfirmation =
                settlementService.confirm(
                        "CASE_SETTLEMENT",
                        v2.version(),
                        merchant,
                        "confirm-v2-merchant");

        assertThat(confirmed.status()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(replayedFinalConfirmation.status())
                .isEqualTo(SettlementStatus.CONFIRMED);
        assertThatThrownBy(
                        () ->
                                settlementService.confirm(
                                        "CASE_SETTLEMENT",
                                        v2.version(),
                                        user,
                                        "confirm-v2-merchant"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(confirmed.confirmedRoles())
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);
        assertThat(proposalRepository.findAllByCaseIdOrderByProposalVersionDesc(
                        "CASE_SETTLEMENT"))
                .hasSize(2);
        assertThat(confirmationRepository.count()).isEqualTo(3);
        assertThat(
                        eventRepository
                                .findByCaseIdAndEventKey(
                                        "CASE_SETTLEMENT",
                                        "settlement-confirmed:2")
                                .orElseThrow()
                                .getEventType())
                .isEqualTo("SETTLEMENT_CONFIRMED");
    }

    @Test
    void theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow() {
        seedHearing("CASE_THREE_ROUNDS");

        var first =
                roundService.completeNext(
                        "CASE_THREE_ROUNDS",
                        new CompleteHearingRoundCommand(1, "{\"round\":1}", false),
                        system);
        var second =
                roundService.completeNext(
                        "CASE_THREE_ROUNDS",
                        new CompleteHearingRoundCommand(1, "{\"round\":2}", false),
                        system);
        var third =
                roundService.completeNext(
                        "CASE_THREE_ROUNDS",
                        new CompleteHearingRoundCommand(1, "{\"round\":3}", false),
                        system);

        assertThat(first.stopReason()).isNull();
        assertThat(second.stopReason()).isNull();
        assertThat(third.stopReason()).isEqualTo(HearingStopReason.MAX_ROUNDS);
        assertThat(third.status()).isEqualTo(HearingRoundStatus.FORCED_CLOSED);
        assertThat(roundRepository.findAllByCaseIdOrderByRoundNoAsc("CASE_THREE_ROUNDS"))
                .hasSize(3);
    }

    @Test
    void factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound() {
        seedHearing("CASE_FACTS_HINT");

        var first =
                roundService.completeNext(
                        "CASE_FACTS_HINT",
                        new CompleteHearingRoundCommand(1, "{\"round\":1}", true),
                        system);

        assertThat(first.stopReason()).isNull();
        assertThat(first.status()).isEqualTo(HearingRoundStatus.COMPLETED);
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_FACTS_HINT", 1, false);
    }

    @Test
    void openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap() {
        seedHearing("CASE_OPENING");

        var first =
                roundService.ensureInitialRoundOpen(
                        "CASE_OPENING",
                        2,
                        "hearing-controller");
        var replayed =
                roundService.ensureInitialRoundOpen(
                        "CASE_OPENING",
                        2,
                        "hearing-controller");

        assertThat(first.roundNo()).isEqualTo(1);
        assertThat(first.status()).isEqualTo(HearingRoundStatus.OPEN);
        assertThat(first.submittedRoles()).isEmpty();
        assertThat(replayed.roundId()).isEqualTo(first.roundId());
        assertThat(roundRepository.findAllByCaseIdOrderByRoundNoAsc("CASE_OPENING"))
                .hasSize(1);
        verify(hearingCourtOrchestrator, never())
                .afterRoundOpenedAfterCommit(
                        "CASE_OPENING", 1, "TRACE_HEARING_ROUND_1");
        assertThat(
                        eventRepository
                                .findByCaseIdAndEventKey(
                                        "CASE_OPENING",
                                        "hearing-round-opened:1")
                                .orElseThrow()
                                .getEventType())
                .isEqualTo("HEARING_ROUND_OPENED");
    }

    @Test
    void hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness() {
        seedHearing("CASE_STATUS_VIEW");
        roundService.ensureInitialRoundOpen(
                "CASE_STATUS_VIEW",
                2,
                "hearing-controller");

        HearingStatusView open = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(open.hearingPhase()).isEqualTo("ROUND_OPEN");
        assertThat(open.phaseLabel()).isEqualTo("本轮陈述中");
        assertThat(open.canCompleteHearing()).isFalse();
        assertThat(open.reviewGateReady()).isFalse();
        assertThat(open.currentRoundNo()).isEqualTo(1);
        assertThat(open.roundStage()).isEqualTo("FACT_STATEMENT");
        assertThat(open.nextStepHint()).contains("完成本轮陈述");

        roundService.recordPartyMessageSubmission(
                "CASE_STATUS_VIEW",
                1,
                "MESSAGE_USER_STATUS",
                "用户说明签收记录与本人实际收货情况不一致，请核验签收人身份。",
                user);
        roundService.recordPartyMessageSubmission(
                "CASE_STATUS_VIEW",
                1,
                "MESSAGE_MERCHANT_STATUS",
                "商家说明订单已发货并有物流签收记录，请核验签收底单。",
                merchant);

        HearingStatusView secondRoundOpen = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(secondRoundOpen.hearingPhase()).isEqualTo("ROUND_OPEN");
        assertThat(secondRoundOpen.phaseLabel()).isEqualTo("本轮陈述中");
        assertThat(secondRoundOpen.canCompleteHearing()).isFalse();
        assertThat(secondRoundOpen.currentRoundNo()).isEqualTo(2);
        assertThat(secondRoundOpen.roundStage()).isEqualTo("EVIDENCE_EXPLANATION");
        assertThat(secondRoundOpen.nextStepHint()).contains("完成本轮陈述");

        roundService.submitParty(
                "CASE_STATUS_VIEW",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"USER\",\"statement\":\"第二轮：用户解释补充证据与签收争议的关系。\"}"),
                user);
        roundService.submitParty(
                "CASE_STATUS_VIEW",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"MERCHANT\",\"statement\":\"第二轮：商家解释物流签收凭证和发货记录。\"}"),
                merchant);
        roundService.submitParty(
                "CASE_STATUS_VIEW",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"USER\",\"statement\":\"第三轮：用户说明不认可仅凭签收记录驳回退款。\"}"),
                user);
        roundService.submitParty(
                "CASE_STATUS_VIEW",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"MERCHANT\",\"statement\":\"第三轮：商家请求按物流签收记录维持不退款。\"}"),
                merchant);

        HearingStatusView waitingDraft = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(waitingDraft.hearingPhase()).isEqualTo("JUDGE_DRAFTING");
        assertThat(waitingDraft.phaseLabel()).isEqualTo("等待裁决草案");
        assertThat(waitingDraft.finalRoundSealed()).isTrue();
        assertThat(waitingDraft.canCompleteHearing()).isFalse();
        assertThat(waitingDraft.currentRoundNo()).isEqualTo(3);
        assertThat(waitingDraft.roundStage()).isEqualTo("REMEDY_CONFIRMATION");
        assertThat(waitingDraft.nextStepHint()).contains("方案确认");
        assertThat(waitingDraft.nextStepHint()).contains("确认或说明异议");
        assertThat(waitingDraft.nextStepHint()).contains("等待 AI 法官生成裁决草案");

        HearingStateEntity hearing =
                hearingStateRepository.saveAndFlush(
                        HearingStateEntity.start(
                                "HEARING_CASE_STATUS_VIEW",
                                "CASE_STATUS_VIEW",
                                "hearing-window-CASE_STATUS_VIEW",
                                "temporal-worker"));
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        "DRAFT_CASE_STATUS_VIEW",
                        "CASE_STATUS_VIEW",
                        hearing.getId(),
                        4,
                        "[\"物流显示签收，但用户称本人未收到包裹\"]",
                        "[\"签收凭证仍需核验签收人身份\"]",
                        "[\"按平台签收争议规则进入人工审核确认\"]",
                        "[\"审核员需核验物流签收底单\"]",
                        "SIGNATURE_PROOF_REVIEW_REQUIRED",
                        new BigDecimal("0.7600"),
                        "AI 法官已基于三轮陈述和证据卷宗生成裁决草案，仍需平台审核员确认。",
                        "python-agent-service/presiding-judge",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));

        HearingStatusView draftReady = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(draftReady.hearingPhase()).isEqualTo("DRAFT_READY");
        assertThat(draftReady.phaseLabel()).isEqualTo("裁决草案已生成");
        assertThat(draftReady.canCompleteHearing()).isTrue();
        assertThat(draftReady.latestDraftId()).isEqualTo("DRAFT_CASE_STATUS_VIEW");
        assertThat(draftReady.nextStepHint()).contains("进入结果页查看草案说明");

        remedyPlanRepository.saveAndFlush(
                RemedyPlanEntity.pendingApproval(
                        "PLAN_CASE_STATUS_VIEW",
                        "CASE_STATUS_VIEW",
                        "DRAFT_CASE_STATUS_VIEW",
                        1,
                        RouteType.SIMPLE_HEARING,
                        RiskLevel.HIGH,
                        "[]",
                        "[]",
                        "[]",
                        "review-orchestrator"));
        reviewPacketRepository.saveAndFlush(
                ReviewPacketEntity.create(
                        "PACKET_CASE_STATUS_VIEW",
                        "CASE_STATUS_VIEW",
                        "PLAN_CASE_STATUS_VIEW",
                        1,
                        "{}",
                        "[]",
                        "[]",
                        "[]",
                        "{}",
                        "{}",
                        "[]",
                        "review-orchestrator"));
        reviewTaskRepository.saveAndFlush(
                ReviewTaskEntity.pending(
                        "REVIEW_CASE_STATUS_VIEW",
                        "CASE_STATUS_VIEW",
                        "PLAN_CASE_STATUS_VIEW",
                        "PACKET_CASE_STATUS_VIEW",
                        "NORMAL",
                        "PLATFORM_REVIEWER",
                        OffsetDateTime.parse("2026-07-04T04:00:00Z"),
                        "review-orchestrator"));

        HearingStatusView reviewGateReady = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(reviewGateReady.hearingPhase()).isEqualTo("REVIEW_GATE_READY");
        assertThat(reviewGateReady.phaseLabel()).isEqualTo("裁决草案已生成");
        assertThat(reviewGateReady.nextStepHint()).contains("进入结果页查看草案说明");
        assertThat(reviewGateReady.nextStepHint()).doesNotContain("平台审核入口");
    }

    @Test
    void completeHearingGeneratesAReviewableDraftOnceAfterTheFinalRoundIsSealed() {
        seedHearing("CASE_FINAL_DRAFT_COMPLETION");
        hearingStateRepository.saveAndFlush(
                HearingStateEntity.start(
                        "HEARING_CASE_FINAL_DRAFT_COMPLETION",
                        "CASE_FINAL_DRAFT_COMPLETION",
                        "hearing-window-CASE_FINAL_DRAFT_COMPLETION",
                        "hearing-bootstrap"));
        roundService.completeNext(
                "CASE_FINAL_DRAFT_COMPLETION",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_COMPLETION",
                new CompleteHearingRoundCommand(2, "{\"round\":2}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_COMPLETION",
                new CompleteHearingRoundCommand(2, "{\"round\":3}", false),
                system);

        HearingStatusView waitingDraft = roundService.status("CASE_FINAL_DRAFT_COMPLETION", user);
        assertThat(waitingDraft.hearingPhase()).isEqualTo("JUDGE_DRAFTING");
        assertThat(waitingDraft.canCompleteHearing()).isFalse();

        HearingStatusView completed = roundService.completeHearing("CASE_FINAL_DRAFT_COMPLETION", user);

        assertThat(completed.canCompleteHearing()).isTrue();
        assertThat(completed.hearingPhase()).isIn("DRAFT_READY", "REVIEW_GATE_READY");
        assertThat(completed.latestDraftId()).isNotBlank();
        assertThat(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(
                        "CASE_FINAL_DRAFT_COMPLETION"))
                .hasValueSatisfying(
                        draft -> {
                            assertThat(draft.getId()).isEqualTo(completed.latestDraftId());
                            assertThat(draft.getDraftStatus()).isEqualTo("PENDING_HUMAN_REVIEW");
                            assertThat(draft.getDraftText()).contains("裁决草案");
                        });
        assertThat(
                        hearingStateRepository
                                .findByCaseId("CASE_FINAL_DRAFT_COMPLETION")
                                .orElseThrow()
                                .getHearingStatus()
                                .name())
                .isEqualTo("COMPLETED");
        assertThat(agentRunRepository.findAllByCaseIdOrderByCreatedAtAsc(
                        "CASE_FINAL_DRAFT_COMPLETION"))
                .hasSize(1)
                .extracting("outputRef")
                .containsExactly(completed.latestDraftId());
        assertThat(
                        eventRepository
                                .findByCaseIdAndEventKey(
                                        "CASE_FINAL_DRAFT_COMPLETION",
                                        "hearing-phase-changed:DRAFT_READY:"
                                                + completed.latestDraftId())
                                .orElseThrow())
                .satisfies(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo("HEARING_PHASE_CHANGED");
                            assertThat(event.getEventJson()).contains("\"hearing_phase\":\"DRAFT_READY\"");
                            assertThat(event.getEventJson())
                                    .contains("\"latest_draft_id\":\"" + completed.latestDraftId() + "\"");
                        });
        verify(hearingAgentClient, times(1)).analyze(any(), any(), any());

        HearingStatusView replay = roundService.completeHearing("CASE_FINAL_DRAFT_COMPLETION", user);

        assertThat(replay.latestDraftId()).isEqualTo(completed.latestDraftId());
        assertThat(draftRepository.findAll()).filteredOn(
                        draft -> "CASE_FINAL_DRAFT_COMPLETION".equals(draft.getCaseId()))
                .hasSize(1);
        verify(hearingAgentClient, times(1)).analyze(any(), any(), any());
    }

    @Test
    void finalDraftRequestIncludesFormalJuryA2AReport() {
        seedHearing("CASE_FINAL_DRAFT_JURY_REPORT");
        hearingStateRepository.saveAndFlush(
                HearingStateEntity.start(
                        "HEARING_CASE_FINAL_DRAFT_JURY_REPORT",
                        "CASE_FINAL_DRAFT_JURY_REPORT",
                        "hearing-window-CASE_FINAL_DRAFT_JURY_REPORT",
                        "hearing-bootstrap"));
        roundService.completeNext(
                "CASE_FINAL_DRAFT_JURY_REPORT",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_JURY_REPORT",
                new CompleteHearingRoundCommand(2, "{\"round\":2}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_JURY_REPORT",
                new CompleteHearingRoundCommand(2, "{\"round\":3}", false),
                system);
        a2aMessageService.record(
                new AgentA2ACommand(
                        "CASE_FINAL_DRAFT_JURY_REPORT",
                        3,
                        "JURY_PANEL",
                        AgentA2AMessageService.PRESIDING_JUDGE,
                        "JURY_REVIEW_REPORT",
                        Map.of(
                                "round_no",
                                3,
                                "review_focus_signal",
                                List.of("用户要求复核签收人身份。")),
                        Map.of(
                                "summary",
                                "评审团认为签收人身份和签收地点仍需重点核对。",
                                "risk_level",
                                "MEDIUM",
                                "confidence_score",
                                75,
                                "review_focus_signal",
                                List.of("用户要求复核签收人身份。")),
                        "REVIEWER_VISIBLE",
                        "RUN_JURY_REPORT_3"));

        roundService.completeHearing("CASE_FINAL_DRAFT_JURY_REPORT", user);

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(hearingAgentClient).analyze(request.capture(), any(), any());
        JsonNode courtroomContext =
                request.getValue().path("hearing_context").path("courtroom_context");
        assertThat(courtroomContext.path("jury_review_report").path("payload").path("summary").asText())
                .contains("签收人身份和签收地点");
        assertThat(courtroomContext.path("jury_a2a_notes").get(0).path("message_type").asText())
                .isEqualTo("JURY_REVIEW_REPORT");
        assertThat(courtroomContext.toString())
                .contains("用户要求复核签收人身份")
                .doesNotContain("SYSTEM_AUDIT_ONLY");
    }

    @Test
    void finalRoundRequiresAFinalDraftVersionInsteadOfReusingEarlierAnalysisDrafts() {
        seedHearing("CASE_FINAL_DRAFT_VERSION");
        HearingStateEntity hearing =
                hearingStateRepository.saveAndFlush(
                        HearingStateEntity.start(
                                "HEARING_CASE_FINAL_DRAFT_VERSION",
                                "CASE_FINAL_DRAFT_VERSION",
                                "hearing-window-CASE_FINAL_DRAFT_VERSION",
                                "hearing-bootstrap"));
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        "DRAFT_CASE_FINAL_DRAFT_VERSION_ROUND_1",
                        "CASE_FINAL_DRAFT_VERSION",
                        hearing.getId(),
                        2,
                        "[]",
                        "[]",
                        "[]",
                        "[\"早期轮次分析草案，不应作为最终裁决草案\"]",
                        "EARLY_ANALYSIS_ONLY",
                        new BigDecimal("0.5000"),
                        "这是第 1 轮后的阶段性分析草案。",
                        "python-agent-service/presiding-judge",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));
        roundService.completeNext(
                "CASE_FINAL_DRAFT_VERSION",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_VERSION",
                new CompleteHearingRoundCommand(2, "{\"round\":2}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_VERSION",
                new CompleteHearingRoundCommand(2, "{\"round\":3}", false),
                system);

        HearingStatusView waitingDraft = roundService.status("CASE_FINAL_DRAFT_VERSION", user);

        assertThat(waitingDraft.hearingPhase()).isEqualTo("JUDGE_DRAFTING");
        assertThat(waitingDraft.latestDraftId()).isNull();
        assertThat(waitingDraft.canCompleteHearing()).isFalse();

        HearingStatusView completed = roundService.completeHearing("CASE_FINAL_DRAFT_VERSION", user);

        assertThat(completed.canCompleteHearing()).isTrue();
        assertThat(completed.latestDraftId()).isNotEqualTo("DRAFT_CASE_FINAL_DRAFT_VERSION_ROUND_1");
        assertThat(
                        draftRepository
                                .findFirstByCaseIdOrderByDraftVersionDesc(
                                        "CASE_FINAL_DRAFT_VERSION")
                                .orElseThrow()
                                .getDraftVersion())
                .isEqualTo(4);
    }

    @Test
    void partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge() {
        seedHearing("CASE_PARTY_ROUND");

        var userSubmission =
                roundService.submitParty(
                        "CASE_PARTY_ROUND",
                        new SubmitHearingRoundCommand(
                                2,
                                "{\"party\":\"USER\",\"statement\":\"我已经完成本轮陈述\"}"),
                        user);

        assertThat(userSubmission.status()).isEqualTo(HearingRoundStatus.WAITING);
        assertThat(userSubmission.roundNo()).isEqualTo(1);
        assertThat(userSubmission.submittedRoles()).containsExactly(ActorRole.USER);
        assertThat(userSubmission.roundDeadlineAt()).isNotNull();
        verifyNoInteractions(hearingWorkflowCoordinator);

        var merchantSubmission =
                roundService.submitParty(
                        "CASE_PARTY_ROUND",
                        new SubmitHearingRoundCommand(
                                2,
                                "{\"party\":\"MERCHANT\",\"statement\":\"商家也完成本轮答辩\"}"),
                        merchant);

        assertThat(merchantSubmission.roundNo()).isEqualTo(2);
        assertThat(merchantSubmission.status()).isEqualTo(HearingRoundStatus.OPEN);
        assertThat(merchantSubmission.submittedRoles()).isEmpty();
        var completedRound =
                roundRepository
                        .findByCaseIdAndRoundNo("CASE_PARTY_ROUND", 1)
                        .orElseThrow();
        assertThat(completedRound.getRoundStatus())
                .isEqualTo(HearingRoundStatus.COMPLETED);
        assertThat(completedRound.getSummaryJson())
                .contains("BOTH_PARTIES_SUBMITTED")
                .contains("双方本轮陈述已提交并封存")
                .doesNotContain("鍙");
        assertThat(completedRound.getSummaryJson())
                .contains("双方本轮陈述已提交并封存")
                .doesNotContain("�")
                .doesNotContain("鍙")
                .doesNotContain("鏈");
        verify(hearingCourtOrchestrator)
                .afterRoundClosedAfterCommit(
                        "CASE_PARTY_ROUND", 1, false, "TRACE_HEARING_ROUND_1");
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_PARTY_ROUND", 1, false);
    }

    @Test
    void bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound() {
        seedHearing("CASE_PARTY_ROUND_CONTINUES");

        roundService.submitParty(
                "CASE_PARTY_ROUND_CONTINUES",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"USER\",\"statement\":\"first user statement\"}"),
                user);

        var nextRound =
                roundService.submitParty(
                        "CASE_PARTY_ROUND_CONTINUES",
                        new SubmitHearingRoundCommand(
                                2,
                                "{\"party\":\"MERCHANT\",\"statement\":\"first merchant answer\"}"),
                        merchant);

        assertThat(nextRound.roundNo()).isEqualTo(2);
        assertThat(nextRound.status()).isEqualTo(HearingRoundStatus.OPEN);
        assertThat(nextRound.submittedRoles()).isEmpty();
        assertThat(roundRepository.findAllByCaseIdOrderByRoundNoAsc(
                        "CASE_PARTY_ROUND_CONTINUES"))
                .extracting("roundNo", "roundStatus")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1, HearingRoundStatus.COMPLETED),
                        org.assertj.core.groups.Tuple.tuple(
                                2, HearingRoundStatus.OPEN));
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_PARTY_ROUND_CONTINUES", 1, false);
    }

    @Test
    void dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce() {
        seedHearing("CASE_ROUND_TIMEOUT");

        var userSubmission =
                roundService.submitParty(
                        "CASE_ROUND_TIMEOUT",
                        new SubmitHearingRoundCommand(
                                2,
                                "{\"party\":\"USER\",\"statement\":\"ready\"}"),
                        user);

        assertThat(userSubmission.status()).isEqualTo(HearingRoundStatus.WAITING);
        verifyNoInteractions(hearingWorkflowCoordinator);

        mutableClock.set(Instant.parse("2026-07-03T01:05:01Z"));
        int expired = roundService.expireDueRounds();

        assertThat(expired).isEqualTo(1);
        var closedRound = roundService.list("CASE_ROUND_TIMEOUT", user).get(0);
        assertThat(closedRound.status()).isEqualTo(HearingRoundStatus.COMPLETED);
        assertThat(closedRound.submittedRoles())
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);
        assertThat(closedRound.summaryJson())
                .contains("ROUND_DEADLINE_EXPIRED")
                .contains("AUTO_TIMEOUT")
                .contains("本轮提交时效已届满")
                .doesNotContain("鏈");
        assertThat(
                        submissionRepository
                                .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                        "CASE_ROUND_TIMEOUT", 1))
                .extracting("submissionSource")
                .containsExactlyInAnyOrder(
                        HearingRoundSubmissionSource.PARTY_ACTION,
                        HearingRoundSubmissionSource.AUTO_TIMEOUT);
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_ROUND_TIMEOUT", 1, false);
    }

    @Test
    void roomMessageStatementsFromBothPartiesCloseRoundAndOpenNextRound() {
        seedHearing("CASE_ROOM_STATEMENT");
        roundService.ensureInitialRoundOpen(
                "CASE_ROOM_STATEMENT",
                2,
                "hearing-controller");

        var afterUserMessage =
                roundService.recordPartyMessageSubmission(
                        "CASE_ROOM_STATEMENT",
                        1,
                        "MESSAGE_USER_HEARING_TEXT",
                        "用户补充：请核验签收人身份和投递照片。",
                        user);

        assertThat(afterUserMessage.status()).isEqualTo(HearingRoundStatus.WAITING);
        assertThat(afterUserMessage.submittedRoles()).containsExactly(ActorRole.USER);
        assertThat(afterUserMessage.currentActorSubmitted()).isTrue();
        assertThat(
                        roundRepository
                                .findByCaseIdAndRoundNo("CASE_ROOM_STATEMENT", 1)
                                .orElseThrow()
                                .getRoundStatus())
                .isEqualTo(HearingRoundStatus.WAITING);
        verifyNoInteractions(hearingWorkflowCoordinator);

        var afterMerchantMessage =
                roundService.recordPartyMessageSubmission(
                        "CASE_ROOM_STATEMENT",
                        1,
                        "MESSAGE_MERCHANT_HEARING_TEXT",
                        "商家补充：需要物流公司出具签收底单和投递照片。",
                        merchant);

        assertThat(afterMerchantMessage.roundNo()).isEqualTo(2);
        assertThat(afterMerchantMessage.status()).isEqualTo(HearingRoundStatus.OPEN);
        assertThat(afterMerchantMessage.submittedRoles()).isEmpty();
        var closedRound =
                roundRepository
                        .findByCaseIdAndRoundNo("CASE_ROOM_STATEMENT", 1)
                        .orElseThrow();
        assertThat(closedRound.getRoundStatus()).isEqualTo(HearingRoundStatus.COMPLETED);
        assertThat(closedRound.getSummaryJson())
                .contains("BOTH_PARTIES_SUBMITTED")
                .contains("MESSAGE_USER_HEARING_TEXT")
                .contains("MESSAGE_MERCHANT_HEARING_TEXT")
                .doesNotContain("AUTO_TIMEOUT");
        assertThat(
                        submissionRepository
                                .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                        "CASE_ROOM_STATEMENT", 1))
                .extracting("submissionSource")
                .containsExactlyInAnyOrder(
                        HearingRoundSubmissionSource.PARTY_ACTION,
                        HearingRoundSubmissionSource.PARTY_ACTION);
        verify(hearingCourtOrchestrator)
                .afterRoundClosedAfterCommit(
                        "CASE_ROOM_STATEMENT", 1, false, "TRACE_HEARING_ROUND_1");
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_ROOM_STATEMENT", 1, false);
    }

    @Test
    void secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion() {
        seedHearing("CASE_EVIDENCE_REVISION");
        evidenceDossierRepository.saveAndFlush(
                EvidenceDossierEntity.frozen(
                        "EVIDENCE_DOSSIER_REVISION_V1",
                        "CASE_EVIDENCE_REVISION",
                        1,
                        "evidence-clerk",
                        "{\"evidence_count\":1}",
                        "[]",
                        """
                        {
                          "fact_evidence_matrix": [
                            {
                              "fact_id": "FACT_SIGNED",
                              "fact": "物流显示包裹已签收",
                              "supporting_evidence": ["EVD_MERCHANT_LOGISTICS"],
                              "opposing_evidence": [],
                              "evidence_strength": "MEDIUM"
                            }
                          ],
                          "handoff_notes": "开庭基线证据矩阵"
                        }
                        """));

        roundService.ensureInitialRoundOpen(
                "CASE_EVIDENCE_REVISION",
                1,
                "hearing-controller");
        roundService.submitParty(
                "CASE_EVIDENCE_REVISION",
                new SubmitHearingRoundCommand(
                        1,
                        "{\"statement\":\"用户称物流显示签收，但本人没有收到包裹。\"}"),
                user);
        roundService.submitParty(
                "CASE_EVIDENCE_REVISION",
                new SubmitHearingRoundCommand(
                        1,
                        "{\"statement\":\"商家称已按订单地址发货，物流系统显示签收。\"}"),
                merchant);

        roundService.submitParty(
                "CASE_EVIDENCE_REVISION",
                new SubmitHearingRoundCommand(
                        1,
                        "{\"statement\":\"用户说明现有证据主要用于证明未实际收到包裹，请核验签收人身份。\"}"),
                user);
        roundService.submitParty(
                "CASE_EVIDENCE_REVISION",
                new SubmitHearingRoundCommand(
                        1,
                        "{\"statement\":\"商家说明物流签收记录来自承运商系统，暂未补充签收照片。\"}"),
                merchant);

        EvidenceDossierEntity active =
                evidenceDossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc("CASE_EVIDENCE_REVISION")
                        .orElseThrow();

        assertThat(active.getDossierVersion()).isEqualTo(2);
        assertThat(active.getMatrixSummaryJson())
                .contains("\"revision_reason\"")
                .contains("\"updated_after_round\":2")
                .contains("\"supersedes_version\":1")
                .contains("\"active_version\":2")
                .contains("\"fact_evidence_matrix\"")
                .contains("用户说明现有证据主要用于证明未实际收到包裹")
                .contains("商家说明物流签收记录来自承运商系统");
        assertThat(eventRepository.findAllByCaseIdOrderBySequenceNoAsc("CASE_EVIDENCE_REVISION"))
                .anySatisfy(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo("EVIDENCE_DOSSIER_REVISED");
                            assertThat(event.getEventJson())
                                    .contains("\"previous_version\":1")
                                    .contains("\"active_version\":2")
                                    .contains("\"updated_after_round\":2");
                        });
    }

    @Test
    void expireDueRoundsSkipsCasesThatAlreadyLeftTheHearingPhase() {
        caseRepository.saveAndFlush(
                FulfillmentCaseEntity.imported(
                        "CASE_POST_HEARING_DUE",
                        "ORDER-CASE_POST_HEARING_DUE",
                        null,
                        "LOG-CASE_POST_HEARING_DUE",
                        "user-local",
                        "merchant-local",
                        "idem-CASE_POST_HEARING_DUE",
                        "SIGNED_NOT_RECEIVED",
                        "履约争议",
                        "庭审已完成，等待平台审核",
                        RiskLevel.HIGH,
                        CaseStatus.WAITING_HUMAN_REVIEW,
                        "HEARING",
                        OffsetDateTime.parse("2026-07-03T04:00:00Z"),
                        "OMS",
                        "EXT-CASE_POST_HEARING_DUE",
                        "external-adapter"));
        roundRepository.saveAndFlush(
                HearingRoundEntity.open(
                        "HEARING_ROUND_POST_HEARING_DUE",
                        "CASE_POST_HEARING_DUE",
                        null,
                        3,
                        2,
                        Instant.parse("2026-07-03T00:59:00Z"),
                        Instant.parse("2026-07-03T00:55:00Z"),
                        "system"));

        int expired = roundService.expireDueRounds();

        assertThat(expired).isZero();
        assertThat(
                        roundRepository
                                .findById("HEARING_ROUND_POST_HEARING_DUE")
                                .orElseThrow()
                                .getRoundStatus())
                .isEqualTo(HearingRoundStatus.OPEN);
        verifyNoInteractions(hearingWorkflowCoordinator);
    }

    private void seedHearing(String caseId) {
        caseRepository.saveAndFlush(
                FulfillmentCaseEntity.imported(
                        caseId,
                        "ORDER-" + caseId,
                        null,
                        "LOG-" + caseId,
                        "user-local",
                        "merchant-local",
                        "idem-" + caseId,
                        "SIGNED_NOT_RECEIVED",
                        "履约争议",
                        "双方进入小法庭",
                        RiskLevel.HIGH,
                        CaseStatus.HEARING_OPEN,
                        "HEARING",
                        OffsetDateTime.parse("2026-07-03T04:00:00Z"),
                        "OMS",
                        "EXT-" + caseId,
                        "external-adapter"));
        roomRepository.saveAndFlush(
                CaseRoomEntity.open(
                        "ROOM_HEARING_" + caseId,
                        caseId,
                        RoomType.HEARING,
                        OffsetDateTime.parse("2026-07-03T01:00:00Z"),
                        "system"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(
                    Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this.instant = new AtomicReference<>(initial);
            this.zone = zone;
        }

        void set(Instant next) {
            instant.set(next);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
