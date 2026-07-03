package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DisputeImportServiceTest {

    @Mock private FulfillmentCaseRepository repository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CasePhaseClockRepository clockRepository;

    private DisputeImportService service;

    @BeforeEach
    void setUp() {
        service =
                new DisputeImportService(
                        repository,
                        roomRepository,
                        clockRepository,
                        new DisputeProperties(
                                Duration.ofHours(2),
                                Duration.ofHours(3),
                                3,
                                Duration.ofSeconds(15),
                                true),
                        Clock.fixed(
                                Instant.parse("2026-07-03T00:00:00Z"),
                                ZoneOffset.UTC));
    }

    @Test
    void importsAnExternalDisputeWithOverviewState() {
        when(repository.findBySourceSystemAndExternalCaseRef("OMS", "EXT-1001"))
                .thenReturn(Optional.empty());
        when(repository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var imported =
                service.importDispute(
                        command("EXT-1001"),
                        new AuthenticatedActor("external-adapter", ActorRole.SYSTEM),
                        "import-ext-1001");

        assertThat(imported.sourceType()).isEqualTo("EXTERNAL_IMPORT");
        assertThat(imported.sourceSystem()).isEqualTo("OMS");
        assertThat(imported.externalCaseReference()).isEqualTo("EXT-1001");
        assertThat(imported.caseStatus()).isEqualTo(CaseStatus.INTAKE_PENDING);
        assertThat(imported.currentRoom()).isEqualTo("INTAKE");
        assertThat(imported.currentDeadlineAt()).isNull();
        verify(repository).save(any(FulfillmentCaseEntity.class));
        verify(roomRepository).save(any(CaseRoomEntity.class));
    }

    @Test
    void importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock() {
        when(repository.findBySourceSystemAndExternalCaseRef("OMS", "EXT-2001"))
                .thenReturn(Optional.empty());
        when(repository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(clockRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.importDispute(
                command(
                        "EXT-2001",
                        CaseStatus.EVIDENCE_OPEN,
                        "EVIDENCE",
                        OffsetDateTime.parse("2026-07-03T02:00:00Z")),
                new AuthenticatedActor("external-adapter", ActorRole.SYSTEM),
                "import-ext-2001");

        var room = org.mockito.ArgumentCaptor.forClass(CaseRoomEntity.class);
        var phaseClock =
                org.mockito.ArgumentCaptor.forClass(CasePhaseClockEntity.class);
        verify(roomRepository).save(room.capture());
        verify(clockRepository).save(phaseClock.capture());
        assertThat(room.getValue().getRoomType()).isEqualTo(RoomType.EVIDENCE);
        assertThat(phaseClock.getValue().getClockType())
                .isEqualTo(PhaseClockType.EVIDENCE_SUBMISSION);
        assertThat(phaseClock.getValue().getDeadlineAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-03T02:00:00Z"));
    }

    @Test
    void returnsTheExistingCaseForTheSameExternalReference() {
        FulfillmentCaseEntity existing =
                FulfillmentCaseEntity.imported(
                        "CASE_EXISTING",
                        "ORDER-1001",
                        "AFTER-1001",
                        "LOG-1001",
                        "user-local",
                        "merchant-local",
                        "import-existing",
                        "SIGNED_NOT_RECEIVED",
                        "签收未收到",
                        "用户表示未收到已签收包裹",
                        RiskLevel.HIGH,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-1001",
                        "external-adapter");
        when(repository.findBySourceSystemAndExternalCaseRef("OMS", "EXT-1001"))
                .thenReturn(Optional.of(existing));

        var imported =
                service.importDispute(
                        command("EXT-1001"),
                        new AuthenticatedActor("external-adapter", ActorRole.SYSTEM),
                        "different-request-key");

        assertThat(imported.id()).isEqualTo("CASE_EXISTING");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsPartyActorsAtTheInternalImportBoundary() {
        assertThatThrownBy(
                        () ->
                                service.importDispute(
                                        command("EXT-1002"),
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        "import-ext-1002"))
                .isInstanceOf(SecurityException.class);
    }

    private static ImportDisputeCommand command(String externalReference) {
        return command(
                externalReference,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null);
    }

    private static ImportDisputeCommand command(
            String externalReference,
            CaseStatus status,
            String room,
            OffsetDateTime deadline) {
        return new ImportDisputeCommand(
                "OMS",
                externalReference,
                "ORDER-1001",
                "AFTER-1001",
                "LOG-1001",
                "user-local",
                "merchant-local",
                "USER",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "用户表示未收到已签收包裹",
                RiskLevel.HIGH,
                status,
                room,
                deadline);
    }
}
