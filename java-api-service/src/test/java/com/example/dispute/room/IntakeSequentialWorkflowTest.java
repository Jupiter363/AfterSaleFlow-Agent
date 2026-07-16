package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeProgressService;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IntakeSequentialWorkflowTest {

    @Test
    void respondentCompletionStartsTheOnlyEvidenceClock() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        FulfillmentCaseRepository caseRepository = mock(FulfillmentCaseRepository.class);
        CaseRoomRepository roomRepository = mock(CaseRoomRepository.class);
        CasePhaseClockRepository clockRepository = mock(CasePhaseClockRepository.class);
        CaseIntakeDossierRepository dossierRepository =
                mock(CaseIntakeDossierRepository.class);
        CaseParticipantRepository participantRepository =
                mock(CaseParticipantRepository.class);
        IntakeProgressService progress = mock(IntakeProgressService.class);
        NotificationService notifications = mock(NotificationService.class);
        CaseLifecycleNotificationService lifecycle =
                mock(CaseLifecycleNotificationService.class);
        EvidenceWindowCoordinator evidenceWindow = mock(EvidenceWindowCoordinator.class);
        CaseEventService events = mock(CaseEventService.class);
        FulfillmentCaseEntity dispute = pendingCase();
        CaseRoomEntity intakeRoom =
                CaseRoomEntity.open(
                        "ROOM_INTAKE_SHARED",
                        dispute.getId(),
                        RoomType.INTAKE,
                        OffsetDateTime.parse("2026-07-14T23:00:00Z"),
                        "user-local");
        String bilateralDossier =
                """
                {
                  "schema_version":"intake_case_detail.v1",
                  "case_fact_matrix":{
                    "schema_version":"case_fact_matrix.v2",
                    "matrix_kind":"BILATERAL_FROZEN"
                  }
                }
                """;
        CaseIntakeDossierEntity dossier =
                CaseIntakeDossierEntity.create(
                        "INTAKE_DOSSIER_SHARED",
                        dispute.getId(),
                        RoomType.INTAKE,
                        bilateralDossier,
                        90,
                        true,
                        "ACCEPTED",
                        4,
                        "dispute-intake-officer");

        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(caseRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(intakeRoom));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.empty());
        when(roomRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(clockRepository.findByCaseIdAndClockType(
                        dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION))
                .thenReturn(Optional.empty());
        when(clockRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(dossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty(), Optional.of(dossier));
        when(participantRepository.saveAll(any())).thenAnswer(call -> call.getArgument(0));

        IntakeRoomService service =
                new IntakeRoomService(
                        caseRepository,
                        roomRepository,
                        clockRepository,
                        dossierRepository,
                        progress,
                        new ParticipantService(participantRepository),
                        notifications,
                        lifecycle,
                        evidenceWindow,
                        events,
                        new DisputeProperties(
                                Duration.ofHours(2),
                                Duration.ofHours(3),
                                Duration.ofMinutes(20),
                                Duration.ofSeconds(15),
                                true),
                        clock);

        var initiatorResult =
                service.confirm(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        new IntakeConfirmationCommand(
                                true, "PRODUCT_QUALITY", RiskLevel.MEDIUM, "确认发起"));

        assertThat(initiatorResult.deadlineAt()).isNull();
        assertThat(initiatorResult.currentRoom()).isEqualTo(RoomType.INTAKE);
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.INTAKE_COMPLETED);
        assertThat(intakeRoom.getRoomStatus()).isEqualTo(RoomStatus.OPEN);
        verify(clockRepository, org.mockito.Mockito.never()).save(any());

        var respondentResult =
                service.confirm(
                        dispute.getId(),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                        new IntakeConfirmationCommand(
                                true, "PRODUCT_QUALITY", RiskLevel.MEDIUM, "确认回应"));

        assertThat(respondentResult.deadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-15T02:00:00Z"));
        assertThat(respondentResult.currentRoom()).isEqualTo(RoomType.EVIDENCE);
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
        assertThat(intakeRoom.getRoomStatus()).isEqualTo(RoomStatus.CLOSED);
        ArgumentCaptor<CasePhaseClockEntity> savedClock =
                ArgumentCaptor.forClass(CasePhaseClockEntity.class);
        verify(clockRepository, times(1)).save(savedClock.capture());
        assertThat(savedClock.getValue().getDeadlineAt())
                .isEqualTo(respondentResult.deadlineAt());
        verify(evidenceWindow, times(1))
                .startAfterCommit(dispute.getId(), Duration.ofHours(2));
        verify(progress).completeInitiator(any(), any(), any());
        verify(progress).completeRespondent(any(), any(), any());
    }

    private static FulfillmentCaseEntity pendingCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_SEQUENTIAL_INTAKE",
                "ORDER-SEQUENTIAL",
                null,
                "LOG-SEQUENTIAL",
                "user-local",
                "merchant-local",
                "idem-sequential",
                "PRODUCT_QUALITY",
                "安装费用争议",
                "用户要求退还额外安装费",
                RiskLevel.MEDIUM,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null,
                "OMS",
                "EXT-SEQUENTIAL",
                "external-adapter");
    }
}
