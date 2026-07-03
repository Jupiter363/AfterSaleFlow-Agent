package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceCompletionService;
import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidencePartyCompletionRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.PhaseClockStatus;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    EvidenceCompletionService.class,
    EvidenceDossierFreezer.class,
    NotificationService.class,
    CaseEventService.class,
    EvidenceRoomIntegrationTest.FixedClockConfiguration.class
})
@Testcontainers
class EvidenceRoomIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_evidence_room")
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
                                + "/dispute_evidence_room");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private EvidenceCompletionService completionService;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private EvidenceDossierRepository dossierRepository;
    @Autowired private EvidenceItemRepository evidenceRepository;
    @Autowired private EvidenceVerificationRepository verificationRepository;
    @Autowired private EvidenceDossierItemRepository dossierItemRepository;
    @Autowired private EvidencePartyCompletionRepository completionRepository;
    @Autowired private CaseRoomRepository roomRepository;
    @Autowired private CasePhaseClockRepository clockRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private CaseTimelineEventRepository eventRepository;
    @MockitoBean private EvidenceWindowCoordinator evidenceWindowCoordinator;
    @MockitoBean private HearingWorkflowCoordinator hearingWorkflowCoordinator;

    @Test
    void bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded() {
        seedEvidenceCase("CASE_EARLY");
        addEvidence("CASE_EARLY", "EVIDENCE_INCLUDED", EvidenceVerificationStatus.VERIFIED);
        addEvidence("CASE_EARLY", "EVIDENCE_REJECTED", EvidenceVerificationStatus.REJECTED);

        completionService.complete(
                "CASE_EARLY",
                new AuthenticatedActor("user-local", ActorRole.USER),
                "user-complete-1");
        completionService.complete(
                "CASE_EARLY",
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "merchant-complete-1");
        caseRepository.flush();

        FulfillmentCaseEntity dispute =
                caseRepository.findById("CASE_EARLY").orElseThrow();
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.HEARING_OPEN);
        assertThat(
                        clockRepository
                                .findByCaseIdAndClockType(
                                        "CASE_EARLY",
                                        PhaseClockType.EVIDENCE_SUBMISSION)
                                .orElseThrow()
                                .getClockStatus())
                .isEqualTo(PhaseClockStatus.COMPLETED_EARLY);
        assertThat(
                        roomRepository
                                .findByCaseIdAndRoomType("CASE_EARLY", RoomType.EVIDENCE)
                                .orElseThrow()
                                .getRoomStatus())
                .isEqualTo(RoomStatus.SEALED);
        assertThat(
                        roomRepository
                                .findByCaseIdAndRoomType("CASE_EARLY", RoomType.HEARING)
                                .orElseThrow()
                                .getRoomStatus())
                .isEqualTo(RoomStatus.OPEN);

        EvidenceDossierEntity frozen =
                dossierRepository
                        .findByCaseIdAndDossierVersion("CASE_EARLY", 1)
                        .orElseThrow();
        assertThat(frozen.getDossierStatus()).isEqualTo("FROZEN");
        assertThat(dossierItemRepository.findAllByDossierIdOrderBySequenceNo(frozen.getId()))
                .extracting(item -> item.getEvidenceId())
                .containsExactly("EVIDENCE_INCLUDED");
        assertThat(
                        completionRepository
                                .findAllByCaseIdAndDossierVersionAndCompletionStatus(
                                        "CASE_EARLY", 1, "COMPLETED"))
                .hasSize(2);
        assertThat(eventRepository.findByCaseIdAndEventKey("CASE_EARLY", "hearing-opened:1"))
                .isPresent();
        assertThat(notificationRepository.findAllByRecipientIdOrderByCreatedAtDesc("user-local"))
                .anySatisfy(
                        notification ->
                                assertThat(notification.getDeepLink())
                                        .isEqualTo("/disputes/CASE_EARLY/hearing"));
    }

    @Test
    void deadlineExpiryWithOnePartySealsAndOpensHearing() {
        seedEvidenceCase("CASE_EXPIRED");
        completionService.complete(
                "CASE_EXPIRED",
                new AuthenticatedActor("user-local", ActorRole.USER),
                "user-complete-expiry");

        var status = completionService.expire("CASE_EXPIRED");
        caseRepository.flush();

        assertThat(status.sealed()).isTrue();
        assertThat(status.nextRoom()).isEqualTo("HEARING");
        assertThat(
                        clockRepository
                                .findByCaseIdAndClockType(
                                        "CASE_EXPIRED",
                                        PhaseClockType.EVIDENCE_SUBMISSION)
                                .orElseThrow()
                                .getClockStatus())
                .isEqualTo(PhaseClockStatus.EXPIRED);
        assertThat(dossierRepository.findByCaseIdAndDossierVersion("CASE_EXPIRED", 1))
                .hasValueSatisfying(
                        dossier -> assertThat(dossier.getDossierStatus()).isEqualTo("FROZEN"));
    }

    @Test
    void deadlineExpiryWithNoSubmissionsStillReportsTheFrozenVersion() {
        seedEvidenceCase("CASE_NO_SUBMISSIONS");

        var status = completionService.expire("CASE_NO_SUBMISSIONS");
        caseRepository.flush();

        assertThat(status.dossierVersion()).isEqualTo(1);
        assertThat(status.userCompleted()).isFalse();
        assertThat(status.merchantCompleted()).isFalse();
        assertThat(status.nextRoom()).isEqualTo("HEARING");
    }

    private void seedEvidenceCase(String caseId) {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        caseId,
                        "ORDER-" + caseId,
                        null,
                        "LOG-" + caseId,
                        "user-local",
                        "merchant-local",
                        "idem-" + caseId,
                        "SIGNED_NOT_RECEIVED",
                        "包裹显示签收但用户未收到",
                        "双方进入举证阶段",
                        RiskLevel.HIGH,
                        CaseStatus.EVIDENCE_OPEN,
                        "EVIDENCE",
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                        "OMS",
                        "EXT-" + caseId,
                        "external-adapter");
        caseRepository.saveAndFlush(dispute);
        EvidenceDossierEntity collecting =
                EvidenceDossierEntity.collecting(
                        "DOSSIER_COLLECTING_" + caseId, caseId, "system");
        dossierRepository.saveAndFlush(collecting);
        CaseRoomEntity evidenceRoom =
                roomRepository.saveAndFlush(
                        CaseRoomEntity.open(
                                "ROOM_EVIDENCE_" + caseId,
                                caseId,
                                RoomType.EVIDENCE,
                                OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                                "system"));
        clockRepository.saveAndFlush(
                CasePhaseClockEntity.running(
                        "CLOCK_EVIDENCE_" + caseId,
                        caseId,
                        evidenceRoom.getId(),
                        PhaseClockType.EVIDENCE_SUBMISSION,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                        "evidence-window-" + caseId,
                        "system"));
    }

    private void addEvidence(
            String caseId, String evidenceId, EvidenceVerificationStatus status) {
        EvidenceDossierEntity collecting =
                dossierRepository.findByCaseIdAndDossierVersion(caseId, 0).orElseThrow();
        evidenceRepository.saveAndFlush(
                EvidenceItemEntity.uploaded(
                        evidenceId,
                        caseId,
                        collecting.getId(),
                        "LOGISTICS_PROOF",
                        "USER_UPLOAD",
                        "USER",
                        "user-local",
                        "evidence-original",
                        caseId + "/" + evidenceId + "/proof.png",
                        "hash-" + evidenceId,
                        "proof.png",
                        "image/png",
                        12,
                        "PARTIES",
                        OffsetDateTime.parse("2026-07-03T00:00:00Z")));
        verificationRepository.saveAndFlush(
                EvidenceVerificationEntity.create(
                        "VERIFY_" + evidenceId,
                        caseId,
                        evidenceId,
                        1,
                        status,
                        "{}",
                        "{}",
                        "[]",
                        false,
                        Instant.parse("2026-07-03T00:30:00Z"),
                        "system",
                        "trace-" + evidenceId));
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
