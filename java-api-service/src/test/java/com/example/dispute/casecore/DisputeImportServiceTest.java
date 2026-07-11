package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ExternalCaseImportTransactionService;
import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplateCatalog;
import com.example.dispute.casecore.application.SingleInstanceImportGate;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.casecore.infrastructure.persistence.repository.SimulatedImportTemplateCursorRepository;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class DisputeImportServiceTest {

    @Mock private FulfillmentCaseRepository repository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CasePhaseClockRepository clockRepository;
    @Mock private ParticipantService participantService;
    @Mock private IntakeAgentTurnService intakeAgentTurnService;
    @Mock private SimulatedImportTemplateCursorRepository simulatedImportCursorRepository;

    private DisputeImportService service;
    private ExternalCaseImportTransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService =
                new ExternalCaseImportTransactionService(
                        repository,
                        roomRepository,
                        clockRepository,
                        participantService,
                        intakeAgentTurnService,
                        simulatedImportCursorRepository,
                        new SimulatedExternalDisputeTemplateCatalog(),
                        new PostCommitSideEffectExecutor(Runnable::run),
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
        service =
                new DisputeImportService(transactionService, new SingleInstanceImportGate());
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
    void startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits() {
        when(repository.findBySourceSystemAndExternalCaseRef("OMS", "EXT-1003"))
                .thenReturn(Optional.empty());
        when(repository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
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
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

            for (TransactionSynchronization synchronization :
                    TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

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
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase() {
        when(repository.findBySourceSystemAndExternalCaseRef("OMS", "EXT-POST-COMMIT-FAILURE"))
                .thenReturn(Optional.empty());
        when(repository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new org.springframework.dao.InvalidDataAccessApiUsageException("transaction closed"))
                .when(intakeAgentTurnService)
                .startInitialTurn(
                        any(String.class),
                        any(AuthenticatedActor.class),
                        any(IntakeLobbySeed.class),
                        any(String.class),
                        any(String.class));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatCode(
                            () ->
                                    service.importDispute(
                                            command("EXT-POST-COMMIT-FAILURE"),
                                            new AuthenticatedActor(
                                                    "external-adapter", ActorRole.SYSTEM),
                                            "import-ext-post-commit-failure"))
                    .doesNotThrowAnyException();

            assertThatCode(
                            () -> {
                                for (TransactionSynchronization synchronization :
                                        TransactionSynchronizationManager.getSynchronizations()) {
                                    synchronization.afterCommit();
                                }
                            })
                    .doesNotThrowAnyException();
            verify(repository).save(any(FulfillmentCaseEntity.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
    void replayRepairsThePersistedRoomInsteadOfTrustingConflictingPayloadState() {
        FulfillmentCaseEntity existing =
                FulfillmentCaseEntity.imported(
                        "CASE_EXISTING_STATE",
                        "ORDER-1001",
                        "AFTER-1001",
                        "LOG-1001",
                        "user-local",
                        "merchant-local",
                        "import-existing-state",
                        "SIGNED_NOT_RECEIVED",
                        "Existing intake dispute",
                        "The persisted case is still in intake.",
                        RiskLevel.HIGH,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-STATE-REPLAY",
                        "external-adapter");
        when(repository.findBySourceSystemAndExternalCaseRef(
                        "OMS",
                        "EXT-STATE-REPLAY"))
                .thenReturn(Optional.of(existing));
        when(roomRepository.save(any()))
                .thenReturn(
                        CaseRoomEntity.open(
                                "ROOM_REPLAY_PAYLOAD",
                                "CASE_EXISTING_STATE",
                                RoomType.EVIDENCE,
                                OffsetDateTime.parse("2026-07-03T00:00:00Z"),
                                "external-adapter"));

        service.importDispute(
                command(
                        "EXT-STATE-REPLAY",
                        CaseStatus.EVIDENCE_OPEN,
                        "EVIDENCE",
                        OffsetDateTime.parse("2026-07-03T02:00:00Z")),
                new AuthenticatedActor(
                        "external-adapter",
                        ActorRole.SYSTEM),
                "state-replay");

        verify(roomRepository)
                .findByCaseIdAndRoomType(
                        "CASE_EXISTING_STATE",
                        RoomType.INTAKE);
        verify(roomRepository, never())
                .findByCaseIdAndRoomType(
                        "CASE_EXISTING_STATE",
                        RoomType.EVIDENCE);
        verify(clockRepository, never()).save(any());
    }

    @Test
    void rejectsReusingAnImportRequestKeyForAnotherExternalCase() {
        FulfillmentCaseEntity firstImport =
                FulfillmentCaseEntity.imported(
                        "CASE_FIRST_IMPORT",
                        "ORDER-1001",
                        "AFTER-1001",
                        "LOG-1001",
                        "user-local",
                        "merchant-local",
                        "shared-import-key",
                        "SIGNED_NOT_RECEIVED",
                        "First imported dispute",
                        "This request key already created another external case.",
                        RiskLevel.HIGH,
                        CaseStatus.INTAKE_PENDING,
                        "INTAKE",
                        null,
                        "OMS",
                        "EXT-FIRST",
                        "external-adapter");
        when(repository.findByCreationIdempotencyKey("shared-import-key"))
                .thenReturn(Optional.of(firstImport));

        assertThatThrownBy(
                        () ->
                                service.importDispute(
                                        command("EXT-SECOND"),
                                        new AuthenticatedActor(
                                                "external-adapter",
                                                ActorRole.SYSTEM),
                                        "shared-import-key"))
                .isInstanceOf(IdempotencyConflictException.class);
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

    @Test
    void simulatedBatchDoesNotOwnATransactionThatAccumulatesItemLocks()
            throws NoSuchMethodException {
        var method =
                DisputeImportService.class.getMethod(
                        "simulateExternalImport",
                        SimulateExternalImportCommand.class,
                        AuthenticatedActor.class,
                        String.class,
                        String.class,
                        String.class);

        assertThat(method.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void simulatedImportDelegatesToTheTransactionalTemplateBoundary()
            throws NoSuchMethodException {
        ExternalCaseImportTransactionService transactionalImporter =
                mock(ExternalCaseImportTransactionService.class);
        DisputeImportService facade =
                new DisputeImportService(transactionalImporter, new SingleInstanceImportGate());
        when(transactionalImporter.simulateExternalImport(
                        any(SimulateExternalImportCommand.class),
                        any(AuthenticatedActor.class),
                        any(String.class),
                        any(String.class),
                        any(String.class)))
                .thenReturn(
                        mock(
                                com.example.dispute.casecore.application
                                        .SimulatedImportResultView.class));

        facade.simulateExternalImport(
                new SimulateExternalImportCommand(
                        1,
                        "watch dispute",
                        RiskLevel.MEDIUM,
                        ActorRole.USER,
                        "user-local",
                        "merchant-local"),
                new AuthenticatedActor("external-adapter", ActorRole.SYSTEM),
                "simulate-batch",
                "TRACE_BATCH",
                "REQ_BATCH");

        verify(transactionalImporter)
                .simulateExternalImport(
                        any(SimulateExternalImportCommand.class),
                        any(AuthenticatedActor.class),
                        org.mockito.ArgumentMatchers.eq("simulate-batch"),
                        org.mockito.ArgumentMatchers.eq("TRACE_BATCH"),
                        org.mockito.ArgumentMatchers.eq("REQ_BATCH"));
        assertThat(
                        ExternalCaseImportTransactionService.class
                                .getMethod(
                                        "simulateExternalImport",
                                        SimulateExternalImportCommand.class,
                                        AuthenticatedActor.class,
                                        String.class,
                                        String.class,
                                        String.class)
                                .getAnnotation(Transactional.class))
                .isNotNull();
    }

    @Test
    void simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay() {
        ExternalCaseImportTransactionService transactionalImporter =
                mock(ExternalCaseImportTransactionService.class);
        DisputeImportService facade =
                new DisputeImportService(transactionalImporter, new SingleInstanceImportGate());
        when(transactionalImporter.simulateExternalImport(
                        any(SimulateExternalImportCommand.class),
                        any(AuthenticatedActor.class),
                        any(String.class),
                        any(String.class),
                        any(String.class)))
                .thenReturn(
                        mock(
                                com.example.dispute.casecore.application
                                        .SimulatedImportResultView.class));

        SimulateExternalImportCommand command =
                new SimulateExternalImportCommand(
                        1,
                        "watch dispute",
                        RiskLevel.MEDIUM,
                        ActorRole.USER,
                        "user-local",
                        "merchant-local");
        AuthenticatedActor actor =
                new AuthenticatedActor("external-adapter", ActorRole.SYSTEM);

        facade.simulateExternalImport(
                command,
                actor,
                "simulate-retry",
                "TRACE_RETRY_FIRST",
                "REQ_RETRY_FIRST");
        facade.simulateExternalImport(
                command,
                actor,
                "simulate-retry",
                "TRACE_RETRY_SECOND",
                "REQ_RETRY_SECOND");

        ArgumentCaptor<String> creationKeys =
                ArgumentCaptor.forClass(String.class);
        verify(transactionalImporter, times(2))
                .simulateExternalImport(
                        any(SimulateExternalImportCommand.class),
                        any(AuthenticatedActor.class),
                        creationKeys.capture(),
                        any(String.class),
                        any(String.class));
        assertThat(creationKeys.getAllValues())
                .containsExactly("simulate-retry", "simulate-retry");
    }

    @Test
    void directImportsOfDifferentBusinessKeysCannotEnterTheTransactionBoundaryTogether()
            throws Exception {
        ExternalCaseImportTransactionService transactionalImporter =
                mock(ExternalCaseImportTransactionService.class);
        DisputeImportService facade =
                new DisputeImportService(transactionalImporter, new SingleInstanceImportGate());
        ImportedDisputeView imported = mock(ImportedDisputeView.class);
        CyclicBarrier requestStart = new CyclicBarrier(2);
        CyclicBarrier transactionOverlap = new CyclicBarrier(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            int current = active.incrementAndGet();
                            maxActive.accumulateAndGet(current, Math::max);
                            try {
                                transactionOverlap.await(500, TimeUnit.MILLISECONDS);
                            } catch (TimeoutException | BrokenBarrierException ignored) {
                                // A serialized caller lets only one invocation reach this boundary.
                            } finally {
                                active.decrementAndGet();
                            }
                            return imported;
                        })
                .when(transactionalImporter)
                .importDispute(
                        any(ImportDisputeCommand.class),
                        any(AuthenticatedActor.class),
                        any(String.class),
                        any(String.class),
                        any(String.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ImportedDisputeView> first =
                    executor.submit(
                            () -> {
                                requestStart.await(5, TimeUnit.SECONDS);
                                return facade.importDispute(
                                        command("EXT-GATE-001"),
                                        new AuthenticatedActor(
                                                "external-adapter", ActorRole.SYSTEM),
                                        "gate-first");
                            });
            Future<ImportedDisputeView> second =
                    executor.submit(
                            () -> {
                                requestStart.await(5, TimeUnit.SECONDS);
                                return facade.importDispute(
                                        command("EXT-GATE-002"),
                                        new AuthenticatedActor(
                                                "external-adapter", ActorRole.SYSTEM),
                                        "gate-second");
                            });

            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(imported);
            assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(imported);
            assertThat(maxActive).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void serializedImportReleasesTheGateAfterFailure() {
        ExternalCaseImportTransactionService transactionalImporter =
                mock(ExternalCaseImportTransactionService.class);
        DisputeImportService facade =
                new DisputeImportService(transactionalImporter, new SingleInstanceImportGate());
        ImportedDisputeView imported = mock(ImportedDisputeView.class);
        when(transactionalImporter.importDispute(
                        any(ImportDisputeCommand.class),
                        any(AuthenticatedActor.class),
                        any(String.class),
                        any(String.class),
                        any(String.class)))
                .thenThrow(new IllegalStateException("simulated import failure"))
                .thenReturn(imported);

        assertThatThrownBy(
                        () ->
                                facade.importDispute(
                                        command("EXT-GATE-FAIL"),
                                        new AuthenticatedActor(
                                                "external-adapter", ActorRole.SYSTEM),
                                        "gate-failure"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated import failure");

        assertThat(
                        facade.importDispute(
                                command("EXT-GATE-RECOVER"),
                                new AuthenticatedActor(
                                        "external-adapter", ActorRole.SYSTEM),
                                "gate-recovery"))
                .isSameAs(imported);
    }

    @Test
    void simulationCommandRejectsCountsAboveOne() {
        assertThatThrownBy(
                        () ->
                                new SimulateExternalImportCommand(
                                        2,
                                        "watch dispute",
                                        RiskLevel.MEDIUM,
                                        ActorRole.USER,
                                        "user-local",
                                        "merchant-local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count must be 1");
    }

    @Test
    void importCommandRejectsNonDemoPartyIds() {
        assertThatThrownBy(
                        () ->
                                new ImportDisputeCommand(
                                        "OMS",
                                        "EXT-WRONG-PARTY",
                                        "ORDER-WRONG-PARTY",
                                        null,
                                        null,
                                        "user-1",
                                        "merchant-local",
                                        "USER",
                                        "SIGNED_NOT_RECEIVED",
                                        "Imported dispute",
                                        "Imported dispute description",
                                        RiskLevel.MEDIUM,
                                        CaseStatus.INTAKE_PENDING,
                                        "INTAKE",
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId must be user-local");
    }

    @Test
    void importCommandRejectsNonDemoMerchantIds() {
        assertThatThrownBy(
                        () ->
                                new ImportDisputeCommand(
                                        "OMS",
                                        "EXT-WRONG-MERCHANT",
                                        "ORDER-WRONG-MERCHANT",
                                        null,
                                        null,
                                        "user-local",
                                        "merchant-1",
                                        "USER",
                                        "SIGNED_NOT_RECEIVED",
                                        "Imported dispute",
                                        "Imported dispute description",
                                        RiskLevel.MEDIUM,
                                        CaseStatus.INTAKE_PENDING,
                                        "INTAKE",
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchantId must be merchant-local");
    }

    @Test
    void simulationCommandRequiresFixedPartiesInInitiatorOrder() {
        assertThatThrownBy(
                        () ->
                                new SimulateExternalImportCommand(
                                        1,
                                        "watch dispute",
                                        RiskLevel.MEDIUM,
                                        ActorRole.USER,
                                        "merchant-local",
                                        "user-local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currentActorId must be user-local");
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
