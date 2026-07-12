package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.domain.NotificationType;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationOutboxRepository;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationRepository;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.RoomMessageService;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.PhaseClockStatus;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
    IntakeRoomService.class,
    ParticipantService.class,
    NotificationService.class,
    CaseLifecycleNotificationService.class,
    CaseEventService.class,
    RoomMessageService.class,
    IntakeRoomServiceIntegrationTest.FixedClockConfiguration.class
})
@Testcontainers
class IntakeRoomServiceIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_intake_room")
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
                                + "/dispute_intake_room");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private IntakeRoomService service;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private CaseParticipantRepository participantRepository;
    @Autowired private CaseRoomRepository roomRepository;
    @Autowired private CasePhaseClockRepository clockRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationOutboxRepository outboxRepository;
    @Autowired private RoomMessageService roomMessageService;
    @Autowired private RoomMessageRepository messageRepository;
    @Autowired private CaseTimelineEventRepository eventRepository;
    @MockitoBean private EvidenceWindowCoordinator evidenceWindowCoordinator;
    @MockitoBean private IntakeAgentTurnService intakeAgentTurnService;
    @MockitoBean private AccessSessionResolver accessSessionResolver;
    @MockitoBean private SessionPermissionService sessionPermissionService;
    @MockitoBean private EvidenceAgentTurnService evidenceAgentTurnService;
    @MockitoBean private HearingRoundService hearingRoundService;

    @Test
    void acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_INTEGRATION",
                        "ORDER-INTEGRATION",
                        null,
                        "LOG-INTEGRATION",
                        "user-local",
                        "merchant-local",
                        "idem-integration",
                        "SIGNED_NOT_RECEIVED",
                        "签收未收到",
                        "用户表示没有收到已签收包裹",
                        RiskLevel.HIGH,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-INTEGRATION",
                        "external-adapter");
        caseRepository.saveAndFlush(dispute);

        service.confirm(
                dispute.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER),
                new IntakeConfirmationCommand(
                        true,
                        "SIGNED_NOT_RECEIVED",
                        RiskLevel.HIGH,
                        "确认信息无误，同意发起争议审理"));
        caseRepository.flush();

        FulfillmentCaseEntity persisted =
                caseRepository.findById(dispute.getId()).orElseThrow();
        assertThat(persisted.getCaseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        assertThat(persisted.getCurrentRoom()).isEqualTo("EVIDENCE");
        assertThat(persisted.getCurrentDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T02:00:00Z"));
        assertThat(participantRepository.findAllByCaseId(dispute.getId()))
                .extracting(participant -> participant.getParticipantRole())
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);
        assertThat(roomRepository.findAllByCaseId(dispute.getId()))
                .extracting(
                        room -> room.getRoomType() + ":" + room.getRoomStatus())
                .containsExactlyInAnyOrder(
                        RoomType.INTAKE + ":" + RoomStatus.CLOSED,
                        RoomType.EVIDENCE + ":" + RoomStatus.OPEN);
        assertThat(
                        clockRepository.findByCaseIdAndClockType(
                                dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION))
                .hasValueSatisfying(
                        phaseClock -> {
                            assertThat(phaseClock.getClockStatus())
                                    .isEqualTo(PhaseClockStatus.RUNNING);
                            assertThat(phaseClock.getDeadlineAt())
                                    .isEqualTo(
                                            OffsetDateTime.parse(
                                                    "2026-07-03T02:00:00Z"));
                        });
        assertThat(
                        notificationRepository
                                .findAllByRecipientIdOrderByCreatedAtDesc(
                                        "merchant-local"))
                .extracting(notification -> notification.getNotificationType())
                .containsExactlyInAnyOrder(
                        NotificationType.DISPUTE_SUMMONS,
                        NotificationType.EVIDENCE_ROOM_OPENED);
        assertThat(
                        notificationRepository
                                .findAllByRecipientIdOrderByCreatedAtDesc(
                                        "merchant-local"))
                .allSatisfy(
                        notification ->
                                assertThat(notification.getDeepLink())
                                        .isEqualTo(
                                                "/disputes/CASE_INTEGRATION/evidence"));
        assertThat(outboxRepository.count()).isEqualTo(2);

        var posted =
                roomMessageService.post(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new RoomMessageCommand(
                                MessageType.PARTY_TEXT,
                                "补充提交开箱照片。",
                                List.of("EVIDENCE_1")),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "msg-integration-1",
                        "TRACE_integration");
        caseRepository.flush();

        assertThat(posted.sequenceNo()).isEqualTo(1);
        assertThat(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(posted.roomId()))
                .singleElement()
                .satisfies(
                        message ->
                                assertThat(message.getMessageText())
                                        .isEqualTo("补充提交开箱照片。"));
        assertThat(
                        eventRepository
                                .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                        dispute.getId(), 0))
                .extracting(
                        event ->
                                event.getSequenceNo()
                                        + ":"
                                        + event.getEventType())
                .containsExactly(
                        "1:EVIDENCE_OPENED",
                        "2:ROOM_MESSAGE_CREATED");
    }

    @Test
    void notAdmissiblePersistsOnlyTheInitiatorAndTerminatesAfterIntake() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_REJECTED_INTEGRATION",
                        "ORDER-REJECTED-INTEGRATION",
                        null,
                        "LOG-REJECTED-INTEGRATION",
                        "user-local",
                        "merchant-local",
                        "idem-rejected-integration",
                        "FULFILLMENT_CONFLICT",
                        "查询物流进度",
                        "用户只是询问物流状态，不构成履约争端",
                        RiskLevel.LOW,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-REJECTED-INTEGRATION",
                        "external-adapter");
        caseRepository.saveAndFlush(dispute);

        var result =
                service.confirm(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        new IntakeConfirmationCommand(
                                false,
                                "NOT_A_FULFILLMENT_DISPUTE",
                                RiskLevel.LOW,
                                "仅为普通物流查询，不予受理"));
        caseRepository.flush();

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.NOT_ADMISSIBLE);
        assertThat(result.currentRoom()).isNull();
        assertThat(result.deadlineAt()).isNull();
        assertThat(caseRepository.findById(dispute.getId()).orElseThrow().getCaseStatus())
                .isEqualTo(CaseStatus.NOT_ADMISSIBLE);
        assertThat(participantRepository.findAllByCaseId(dispute.getId()))
                .extracting(participant -> participant.getParticipantRole())
                .containsExactly(ActorRole.USER);
        assertThat(roomRepository.findAllByCaseId(dispute.getId()))
                .extracting(room -> room.getRoomType() + ":" + room.getRoomStatus())
                .containsExactly(RoomType.INTAKE + ":" + RoomStatus.CLOSED);
        assertThat(clockRepository.findByCaseIdAndClockType(
                        dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION))
                .isEmpty();
        assertThat(notificationRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(
                        eventRepository.findByCaseIdAndEventKey(
                                dispute.getId(),
                                "intake-confirmed:" + dispute.getId()))
                .hasValueSatisfying(
                        event -> assertThat(event.getEventType()).isEqualTo("INTAKE_REJECTED"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
