package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.CompleteHearingRoundCommand;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import com.example.dispute.hearing.application.SettlementProposalCommand;
import com.example.dispute.hearing.application.SettlementService;
import com.example.dispute.hearing.application.SettlementVersionConflictException;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingStopReason;
import com.example.dispute.hearing.domain.SettlementStatus;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementConfirmationRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementProposalRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
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
    @MockitoBean private HearingWorkflowCoordinator hearingWorkflowCoordinator;

    private AuthenticatedActor user;
    private AuthenticatedActor merchant;
    private AuthenticatedActor system;

    @BeforeEach
    void setUp() {
        user = new AuthenticatedActor("user-local", ActorRole.USER);
        merchant = new AuthenticatedActor("merchant-local", ActorRole.MERCHANT);
        system = new AuthenticatedActor("hearing-controller", ActorRole.SYSTEM);
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

        settlementService.confirm(
                "CASE_SETTLEMENT", v2.version(), user, "confirm-v2-user");
        var confirmed =
                settlementService.confirm(
                        "CASE_SETTLEMENT",
                        v2.version(),
                        merchant,
                        "confirm-v2-merchant");

        assertThat(confirmed.status()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(confirmed.confirmedRoles())
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);
        assertThat(proposalRepository.findAllByCaseIdOrderByProposalVersionDesc(
                        "CASE_SETTLEMENT"))
                .hasSize(2);
        assertThat(confirmationRepository.count()).isEqualTo(3);
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
        Clock fixedClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
