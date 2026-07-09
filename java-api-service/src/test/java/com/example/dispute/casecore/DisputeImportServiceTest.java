package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ExternalDisputeSimulationClient;
import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.casecore.application.SimulatedExternalDispute;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class DisputeImportServiceTest {

    @Mock private FulfillmentCaseRepository repository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CasePhaseClockRepository clockRepository;
    @Mock private ParticipantService participantService;
    @Mock private IntakeAgentTurnService intakeAgentTurnService;
    @Mock private ExternalDisputeSimulationClient simulationClient;

    private DisputeImportService service;

    @BeforeEach
    void setUp() {
        service =
                new DisputeImportService(
                        repository,
                        roomRepository,
                        clockRepository,
                        participantService,
                        intakeAgentTurnService,
                        simulationClient,
                        new DisputeProperties(
                                Duration.ofHours(2),
                                Duration.ofHours(3),
                                Duration.ofMinutes(5),
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
        assertThat(imported.initiatorRole()).isEqualTo("USER");
        assertThat(imported.orderId()).isEqualTo("ORDER-1001");
        assertThat(imported.afterSaleId()).isEqualTo("AFTER-1001");
        assertThat(imported.logisticsId()).isEqualTo("LOG-1001");
        assertThat(imported.disputeType()).isEqualTo("SIGNED_NOT_RECEIVED");
        assertThat(imported.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(imported.title()).isEqualTo("签收未收到");
        assertThat(imported.description()).isEqualTo("用户表示未收到已签收包裹");
        assertThat(imported.pendingAction()).isEqualTo("COMPLETE_INTAKE");
        var savedCase =
                org.mockito.ArgumentCaptor.forClass(FulfillmentCaseEntity.class);
        verify(repository).save(savedCase.capture());
        assertThat(savedCase.getValue().getCaseType()).isEqualTo("DISPUTE");
        assertThat(savedCase.getValue().getInitiatorRole()).isEqualTo(ActorRole.USER);
        verify(participantService)
                .ensureImportedParties(
                        any(FulfillmentCaseEntity.class),
                        any(AuthenticatedActor.class),
                        any(OffsetDateTime.class));
        verify(roomRepository).save(any(CaseRoomEntity.class));
        ArgumentCaptor<AuthenticatedActor> intakeActor =
                ArgumentCaptor.forClass(AuthenticatedActor.class);
        ArgumentCaptor<IntakeLobbySeed> seed = ArgumentCaptor.forClass(IntakeLobbySeed.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        any(String.class),
                        intakeActor.capture(),
                        seed.capture(),
                        any(String.class),
                        any(String.class));
        assertThat(intakeActor.getValue().actorId()).isEqualTo("user-local");
        assertThat(intakeActor.getValue().role()).isEqualTo(ActorRole.USER);
        assertThat(seed.getValue().orderReference()).isEqualTo("ORDER-1001");
        assertThat(seed.getValue().afterSalesReference()).isEqualTo("AFTER-1001");
        assertThat(seed.getValue().logisticsReference()).isEqualTo("LOG-1001");
        assertThat(seed.getValue().initiatorRole()).isEqualTo("USER");
        assertThat(seed.getValue().rawText()).isEqualTo("用户表示未收到已签收包裹");
    }

    @Test
    void defersInitialIntakeTurnUntilTheImportTransactionCommits() {
        when(repository.findBySourceSystemAndExternalCaseRef("OMS", "EXT-1003"))
                .thenReturn(Optional.empty());
        when(repository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        List<TransactionSynchronization> synchronizations;
        try {
            service.importDispute(
                    command("EXT-1003"),
                    new AuthenticatedActor("external-adapter", ActorRole.SYSTEM),
                    "import-ext-1003");

            verify(intakeAgentTurnService, never())
                    .startInitialTurn(
                            any(String.class),
                            any(AuthenticatedActor.class),
                            any(IntakeLobbySeed.class),
                            any(String.class),
                            any(String.class));
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(synchronizations).isNotEmpty();
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<AuthenticatedActor> intakeActor =
                ArgumentCaptor.forClass(AuthenticatedActor.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        any(String.class),
                        intakeActor.capture(),
                        any(IntakeLobbySeed.class),
                        any(String.class),
                        any(String.class));
        assertThat(intakeActor.getValue().actorId()).isEqualTo("user-local");
    }

    @Test
    void importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby() {
        when(repository.findBySourceSystemAndExternalCaseRef("OMS", "EXT-CLAIM"))
                .thenReturn(Optional.empty());
        when(repository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.importDispute(
                commandWithClaimSeed("EXT-CLAIM"),
                new AuthenticatedActor("external-adapter", ActorRole.SYSTEM),
                "import-ext-claim");

        ArgumentCaptor<IntakeLobbySeed> seed = ArgumentCaptor.forClass(IntakeLobbySeed.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        any(String.class),
                        any(AuthenticatedActor.class),
                        seed.capture(),
                        any(String.class),
                        any(String.class));

        assertThat(seed.getValue().requestedOutcomeHint()).isEqualTo("REFUND");
        assertThat(seed.getValue().claimResolutionSeed().requestedResolution())
                .isEqualTo("REFUND");
        assertThat(seed.getValue().claimResolutionSeed().requestedItems())
                .isEqualTo("儿童手表 1 件");
        assertThat(seed.getValue().respondentAttitudeSeed().attitude())
                .isEqualTo("NOT_RESPONDED");
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
        verify(intakeAgentTurnService, never())
                .startInitialTurn(
                        any(String.class),
                        any(AuthenticatedActor.class),
                        any(IntakeLobbySeed.class),
                        any(String.class),
                        any(String.class));
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

    @Test
    void simulatesExternalDisputesWithLlmThenImportsThroughTheOfficialPath() {
        when(simulationClient.simulate(any(), any(), any()))
                .thenReturn(
                        List.of(
                                new SimulatedExternalDispute(
                                        "LLM_SIMULATED_OMS",
                                        "SIM-20260706-001",
                                        "ORDER-20260706-4201",
                                        "AS-20260706-4201",
                                        "SF-20260706-4201",
                                        "user-local",
                                        "merchant-local",
                                        "MERCHANT",
                                        "QUALITY_DISPUTE",
                                        "商家发起手表故障争议",
                                        "商家认为用户提交的故障视频与售后检测结果不一致，需要平台受理。",
                                        RiskLevel.MEDIUM)));
        when(repository.findBySourceSystemAndExternalCaseRef(
                        "LLM_SIMULATED_OMS", "SIM-20260706-001"))
                .thenReturn(Optional.empty());
        when(repository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result =
                service.simulateExternalImport(
                        new SimulateExternalImportCommand(
                                1,
                                "手表售后争议",
                                RiskLevel.MEDIUM,
                                ActorRole.MERCHANT,
                                "merchant-local",
                                "user-local"),
                        new AuthenticatedActor("external-adapter", ActorRole.SYSTEM),
                        "simulate-import-001",
                        "TRACE_SIM",
                        "REQ_SIM");

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).sourceType()).isEqualTo("EXTERNAL_IMPORT");
        assertThat(result.items().get(0).externalCaseReference())
                .isEqualTo("SIM-20260706-001");
        assertThat(result.items().get(0).initiatorRole()).isEqualTo("MERCHANT");
        assertThat(result.items().get(0).title()).isEqualTo("商家发起手表故障争议");
        assertThat(result.items().get(0).orderId()).isEqualTo("ORDER-20260706-4201");

        var savedCase =
                org.mockito.ArgumentCaptor.forClass(FulfillmentCaseEntity.class);
        verify(repository).save(savedCase.capture());
        assertThat(savedCase.getValue().getInitiatorRole()).isEqualTo(ActorRole.MERCHANT);
        ArgumentCaptor<AuthenticatedActor> intakeActor =
                ArgumentCaptor.forClass(AuthenticatedActor.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        any(String.class),
                        intakeActor.capture(),
                        any(IntakeLobbySeed.class),
                        any(String.class),
                        any(String.class));
        assertThat(intakeActor.getValue().actorId()).isEqualTo("merchant-local");
        assertThat(intakeActor.getValue().role()).isEqualTo(ActorRole.MERCHANT);
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

    private static ImportDisputeCommand commandWithClaimSeed(String externalReference) {
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
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null,
                "REFUND",
                new IntakeLobbySeed.ClaimResolutionSeed(
                        "USER",
                        "REFUND",
                        null,
                        "儿童手表 1 件",
                        "用户称物流签收但本人未收到包裹，希望退款。",
                        "我没收到包裹，希望退款"),
                new IntakeLobbySeed.RespondentAttitudeSeed(
                        "MERCHANT",
                        "NOT_RESPONDED",
                        "商家尚未在接待室表达态度。",
                        "尚未回应",
                        0.5));
    }
}
