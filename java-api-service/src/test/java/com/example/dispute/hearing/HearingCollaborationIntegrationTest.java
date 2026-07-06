package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.CompleteHearingRoundCommand;
import com.example.dispute.hearing.application.HearingCourtOrchestrator;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import com.example.dispute.hearing.application.SettlementProposalCommand;
import com.example.dispute.hearing.application.SettlementService;
import com.example.dispute.hearing.application.SettlementVersionConflictException;
import com.example.dispute.hearing.application.SubmitHearingRoundCommand;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.hearing.domain.HearingStopReason;
import com.example.dispute.hearing.domain.SettlementStatus;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementConfirmationRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementProposalRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private CaseRoomRepository roomRepository;
    @Autowired private SettlementProposalRepository proposalRepository;
    @Autowired private SettlementConfirmationRepository confirmationRepository;
    @Autowired private HearingRoundRepository roundRepository;
    @Autowired private HearingRoundPartySubmissionRepository submissionRepository;
    @Autowired private CaseTimelineEventRepository eventRepository;
    @Autowired private MutableClock mutableClock;
    @MockitoBean private HearingWorkflowCoordinator hearingWorkflowCoordinator;
    @MockitoBean private HearingCourtOrchestrator hearingCourtOrchestrator;
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
    void openingHearingCreatesInitialRoundAndAsksJudgeForOpeningOnce() {
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
        verify(hearingCourtOrchestrator, times(1))
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
        assertThat(completedRound.getSummaryJson()).contains("BOTH_PARTIES_SUBMITTED");
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
                .contains("AUTO_TIMEOUT");
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
