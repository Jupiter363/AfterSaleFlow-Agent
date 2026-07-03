package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationOutboxRepository;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationRepository;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.RoomMessageService;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    IntakeRoomService.class,
    ParticipantService.class,
    NotificationService.class,
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
                .singleElement()
                .satisfies(
                        notification ->
                                assertThat(notification.getDeepLink())
                                        .isEqualTo(
                                                "/disputes/CASE_INTEGRATION/evidence"));
        assertThat(outboxRepository.count()).isEqualTo(1);

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
                .singleElement()
                .satisfies(
                        event ->
                                assertThat(event.getSequenceNo()).isEqualTo(1));
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
