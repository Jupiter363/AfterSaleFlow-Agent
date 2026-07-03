package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseParticipantEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
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
class IntakeRoomServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CasePhaseClockRepository phaseClockRepository;
    @Mock private NotificationService notificationService;
    @Mock private EvidenceWindowCoordinator evidenceWindowCoordinator;

    private IntakeRoomService service;

    @BeforeEach
    void setUp() {
        ParticipantService participants = new ParticipantService(participantRepository);
        service =
                new IntakeRoomService(
                        caseRepository,
                        roomRepository,
                        phaseClockRepository,
                        participants,
                        notificationService,
                        evidenceWindowCoordinator,
                        CLOCK);
        when(caseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(participantRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void acceptedIntakeInvitesBothPartiesAndOpensATwoHourEvidenceWindow() {
        FulfillmentCaseEntity dispute = pendingCase("CASE_ACCEPTED");
        when(caseRepository.findByIdForUpdate("CASE_ACCEPTED"))
                .thenReturn(Optional.of(dispute));
        when(phaseClockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result =
                service.confirm(
                        "CASE_ACCEPTED",
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        new IntakeConfirmationCommand(
                                true,
                                "SIGNED_NOT_RECEIVED",
                                RiskLevel.HIGH,
                                "确认信息无误，同意发起争议审理"));

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        assertThat(result.currentRoom()).isEqualTo(RoomType.EVIDENCE);
        assertThat(result.deadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T02:00:00Z"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<CaseParticipantEntity>> participants =
                ArgumentCaptor.forClass(Iterable.class);
        verify(participantRepository).saveAll(participants.capture());
        assertThat(participants.getValue())
                .extracting(CaseParticipantEntity::getParticipantRole)
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);

        ArgumentCaptor<CaseRoomEntity> rooms = ArgumentCaptor.forClass(CaseRoomEntity.class);
        verify(roomRepository, org.mockito.Mockito.times(2)).save(rooms.capture());
        assertThat(rooms.getAllValues())
                .anySatisfy(
                        room -> {
                            assertThat(room.getRoomType()).isEqualTo(RoomType.INTAKE);
                            assertThat(room.getRoomStatus()).isEqualTo(RoomStatus.CLOSED);
                        })
                .anySatisfy(
                        room -> {
                            assertThat(room.getRoomType()).isEqualTo(RoomType.EVIDENCE);
                            assertThat(room.getRoomStatus()).isEqualTo(RoomStatus.OPEN);
                        });

        ArgumentCaptor<CasePhaseClockEntity> phaseClock =
                ArgumentCaptor.forClass(CasePhaseClockEntity.class);
        verify(phaseClockRepository).save(phaseClock.capture());
        assertThat(phaseClock.getValue().getClockType())
                .isEqualTo(PhaseClockType.EVIDENCE_SUBMISSION);
        assertThat(phaseClock.getValue().getDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T02:00:00Z"));
        ArgumentCaptor<NotificationCommand> summons =
                ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notificationService).send(summons.capture());
        assertThat(summons.getValue().recipientId()).isEqualTo("merchant-local");
        assertThat(summons.getValue().notificationType())
                .isEqualTo(NotificationType.DISPUTE_SUMMONS);
        assertThat(summons.getValue().deepLink())
                .isEqualTo("/disputes/CASE_ACCEPTED/evidence");
    }

    @Test
    void notAdmissibleEndsAfterIntakeWithoutInvitingMerchantOrOpeningEvidence() {
        FulfillmentCaseEntity dispute = pendingCase("CASE_REJECTED");
        when(caseRepository.findByIdForUpdate("CASE_REJECTED"))
                .thenReturn(Optional.of(dispute));

        var result =
                service.confirm(
                        "CASE_REJECTED",
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        new IntakeConfirmationCommand(
                                false,
                                "NOT_A_FULFILLMENT_DISPUTE",
                                RiskLevel.LOW,
                                "确认本次请求不构成履约争端"));

        assertThat(result.caseStatus()).isEqualTo(CaseStatus.NOT_ADMISSIBLE);
        assertThat(result.currentRoom()).isNull();
        assertThat(result.deadlineAt()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<CaseParticipantEntity>> participants =
                ArgumentCaptor.forClass(Iterable.class);
        verify(participantRepository).saveAll(participants.capture());
        assertThat(participants.getValue())
                .extracting(CaseParticipantEntity::getParticipantRole)
                .containsExactly(ActorRole.USER);
        verify(phaseClockRepository, never()).save(any());
        verify(notificationService, never()).send(any());

        ArgumentCaptor<CaseRoomEntity> rooms = ArgumentCaptor.forClass(CaseRoomEntity.class);
        verify(roomRepository).save(rooms.capture());
        assertThat(rooms.getAllValues())
                .extracting(CaseRoomEntity::getRoomType)
                .containsExactly(RoomType.INTAKE);
    }

    private static FulfillmentCaseEntity pendingCase(String id) {
        return FulfillmentCaseEntity.imported(
                id,
                "ORDER-" + id,
                null,
                "LOG-" + id,
                "user-local",
                "merchant-local",
                "idem-" + id,
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "用户表示没有收到已签收包裹",
                RiskLevel.HIGH,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null,
                "OMS",
                "EXT-" + id,
                "external-adapter");
    }
}
