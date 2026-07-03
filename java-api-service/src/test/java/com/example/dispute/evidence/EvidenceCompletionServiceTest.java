package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceCompletionService;
import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidencePartyCompletionEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidencePartyCompletionRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.PhaseClockStatus;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomStatus;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceCompletionServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidencePartyCompletionRepository completionRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CasePhaseClockRepository clockRepository;
    @Mock private EvidenceDossierFreezer dossierFreezer;
    @Mock private EvidenceWindowCoordinator evidenceWindowCoordinator;
    @Mock private CaseEventService caseEventService;
    @Mock private NotificationService notificationService;
    @Mock private HearingWorkflowCoordinator hearingWorkflowCoordinator;

    private EvidenceCompletionService service;
    private FulfillmentCaseEntity dispute;
    private CaseRoomEntity evidenceRoom;
    private CasePhaseClockEntity evidenceClock;

    @BeforeEach
    void setUp() {
        Clock clock =
                Clock.fixed(
                        Instant.parse("2026-07-03T01:00:00Z"),
                        ZoneOffset.UTC);
        service =
                new EvidenceCompletionService(
                        caseRepository,
                        completionRepository,
                        roomRepository,
                        clockRepository,
                        dossierFreezer,
                        evidenceWindowCoordinator,
                        caseEventService,
                        notificationService,
                        hearingWorkflowCoordinator,
                        new DisputeProperties(
                                Duration.ofHours(2),
                                Duration.ofHours(3),
                                3,
                                Duration.ofSeconds(15),
                                true),
                        clock);
        dispute =
                FulfillmentCaseEntity.imported(
                        "CASE_EVIDENCE_COMPLETE",
                        "ORDER-1",
                        null,
                        "LOG-1",
                        "user-local",
                        "merchant-local",
                        "idem-complete",
                        "SIGNED_NOT_RECEIVED",
                        "签收未收到",
                        "双方举证中",
                        RiskLevel.HIGH,
                        CaseStatus.EVIDENCE_OPEN,
                        "EVIDENCE",
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                        "OMS",
                        "EXT-COMPLETE",
                        "external-adapter");
        evidenceRoom =
                CaseRoomEntity.open(
                        "ROOM_EVIDENCE",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        "system");
        evidenceClock =
                CasePhaseClockEntity.running(
                        "CLOCK_EVIDENCE",
                        dispute.getId(),
                        evidenceRoom.getId(),
                        PhaseClockType.EVIDENCE_SUBMISSION,
                        OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                        OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                        "evidence-window",
                        "system");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(dossierFreezer.targetVersion(dispute.getId())).thenReturn(1);
    }

    @Test
    void bothPartyCompletionsSealEvidenceEarlyAndOpenTheThreeHourHearing() {
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(evidenceRoom));
        when(clockRepository.findByCaseIdAndClockType(
                        dispute.getId(), PhaseClockType.EVIDENCE_SUBMISSION))
                .thenReturn(Optional.of(evidenceClock));
        when(completionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(clockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(caseRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "user-complete-1"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "merchant-complete-1"))
                .thenReturn(Optional.empty());
        when(completionRepository.countByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(1L, 2L);

        service.complete(
                dispute.getId(),
                new AuthenticatedActor("user-local", ActorRole.USER),
                "user-complete-1");
        var result =
                service.complete(
                        dispute.getId(),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                        "merchant-complete-1");

        assertThat(result.allPartiesCompleted()).isTrue();
        assertThat(evidenceRoom.getRoomStatus()).isEqualTo(RoomStatus.SEALED);
        assertThat(evidenceClock.getClockStatus())
                .isEqualTo(PhaseClockStatus.COMPLETED_EARLY);
        assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.HEARING_OPEN);
        assertThat(dispute.getCurrentRoom()).isEqualTo("HEARING");
        assertThat(dispute.getCurrentDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T04:00:00Z"));
    }

    @Test
    void repeatedCompletionByTheSameRoleUsesTheExistingPhaseConfirmation() {
        EvidencePartyCompletionEntity existing =
                EvidencePartyCompletionEntity.completed(
                        "EVIDENCE_COMPLETE_EXISTING",
                        dispute.getId(),
                        1,
                        ActorRole.USER,
                        "user-local",
                        "user-complete-original",
                        Instant.parse("2026-07-03T00:30:00Z"));
        when(completionRepository.findByCaseIdAndIdempotencyKey(
                        dispute.getId(), "user-complete-retry"))
                .thenReturn(Optional.empty());
        when(completionRepository.findByCaseIdAndDossierVersionAndParticipantRole(
                        dispute.getId(), 1, ActorRole.USER))
                .thenReturn(Optional.of(existing));
        when(completionRepository.countByCaseIdAndDossierVersionAndCompletionStatus(
                        dispute.getId(), 1, "COMPLETED"))
                .thenReturn(1L);

        var result =
                service.complete(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "user-complete-retry");

        assertThat(result.dossierVersion()).isEqualTo(1);
        assertThat(result.allPartiesCompleted()).isFalse();
    }
}
