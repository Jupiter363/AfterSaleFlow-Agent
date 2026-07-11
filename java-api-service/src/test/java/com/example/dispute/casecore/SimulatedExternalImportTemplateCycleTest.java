package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ExternalCaseImportTransactionService;
import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplate;
import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplateCatalog;
import com.example.dispute.casecore.application.SingleInstanceImportGate;
import com.example.dispute.casecore.infrastructure.persistence.entity.SimulatedImportTemplateCursorEntity;
import com.example.dispute.casecore.infrastructure.persistence.repository.SimulatedImportTemplateCursorRepository;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SimulatedExternalImportTemplateCycleTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private CasePhaseClockRepository clockRepository;
    @Mock private ParticipantService participantService;
    @Mock private IntakeAgentTurnService intakeAgentTurnService;
    @Mock private SimulatedImportTemplateCursorRepository cursorRepository;

    private final SimulatedExternalDisputeTemplateCatalog catalog =
            new SimulatedExternalDisputeTemplateCatalog();
    private final SimulatedImportTemplateCursorEntity cursor =
            new SimulatedImportTemplateCursorEntity(
                    SimulatedImportTemplateCursorEntity.CURSOR_ID, 1);
    private final Map<String, FulfillmentCaseEntity> casesByCreationKey = new HashMap<>();
    private final AtomicReference<String> activeCreationKey = new AtomicReference<>();

    private DisputeImportService facade;

    @BeforeEach
    void setUp() {
        when(caseRepository.findByCreationIdempotencyKey(anyString()))
                .thenAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            activeCreationKey.set(key);
                            return Optional.ofNullable(casesByCreationKey.get(key));
                        });
        when(caseRepository.findBySourceSystemAndExternalCaseRef(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(caseRepository.save(any(FulfillmentCaseEntity.class)))
                .thenAnswer(
                        invocation -> {
                            FulfillmentCaseEntity saved = invocation.getArgument(0);
                            casesByCreationKey.put(activeCreationKey.get(), saved);
                            return saved;
                        });
        when(roomRepository.findByCaseIdAndRoomType(anyString(), any(RoomType.class)))
                .thenReturn(Optional.empty());
        when(roomRepository.save(any(CaseRoomEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorRepository.findByIdForUpdate(SimulatedImportTemplateCursorEntity.CURSOR_ID))
                .thenReturn(Optional.of(cursor));
        when(cursorRepository.save(any(SimulatedImportTemplateCursorEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExternalCaseImportTransactionService transactionService =
                transactionService(new PostCommitSideEffectExecutor(Runnable::run));
        facade = new DisputeImportService(transactionService, new SingleInstanceImportGate());
    }

    @Test
    void importsTemplatesOneThroughTwentyThenCyclesBackToOne() {
        List<String> titles = new ArrayList<>();
        List<String> externalReferences = new ArrayList<>();

        for (int index = 1; index <= 21; index++) {
            var item =
                    facade.simulateExternalImport(
                                    command(ActorRole.USER),
                                    systemActor(),
                                    "template-sequence-" + index,
                                    "trace-" + index,
                                    "request-" + index)
                            .items()
                            .getFirst();
            titles.add(item.title());
            externalReferences.add(item.externalCaseReference());
        }

        assertThat(titles.subList(0, 20))
                .containsExactlyElementsOf(
                        catalog.all().stream()
                                .map(SimulatedExternalDisputeTemplate::title)
                                .toList());
        assertThat(titles.get(20)).isEqualTo(catalog.get(1).title());
        assertThat(externalReferences).doesNotHaveDuplicates();
        assertThat(externalReferences)
                .allMatch(reference -> reference.startsWith("SIM-T"));
        assertThat(cursor.getNextTemplateNo()).isEqualTo(2);
        verify(cursorRepository, times(21))
                .findByIdForUpdate(SimulatedImportTemplateCursorEntity.CURSOR_ID);
    }

    @Test
    void replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor() {
        var first =
                facade.simulateExternalImport(
                                command(ActorRole.MERCHANT),
                                systemActor(),
                                "stable-replay-key",
                                "trace-first",
                                "request-first")
                        .items()
                        .getFirst();
        int cursorAfterFirstImport = cursor.getNextTemplateNo();

        var replay =
                facade.simulateExternalImport(
                                command(ActorRole.MERCHANT),
                                systemActor(),
                                "stable-replay-key",
                                "trace-replay",
                                "request-replay")
                        .items()
                        .getFirst();

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.externalCaseReference()).isEqualTo(first.externalCaseReference());
        assertThat(cursor.getNextTemplateNo()).isEqualTo(cursorAfterFirstImport);
        verify(cursorRepository, times(1))
                .findByIdForUpdate(SimulatedImportTemplateCursorEntity.CURSOR_ID);
        verify(cursorRepository, times(1)).save(cursor);
    }

    @Test
    void mapsUserInitiatedTemplateClaimAndMerchantAttitudeIntoTheIntakeSeed() {
        facade.simulateExternalImport(
                command(ActorRole.USER),
                systemActor(),
                "seed-contract-key",
                "trace-seed",
                "request-seed");

        ArgumentCaptor<IntakeLobbySeed> seed = ArgumentCaptor.forClass(IntakeLobbySeed.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        anyString(),
                        any(AuthenticatedActor.class),
                        seed.capture(),
                        anyString(),
                        anyString());
        assertThat(seed.getValue().claimResolutionSeed().initiatorRole()).isEqualTo("USER");
        assertThat(seed.getValue().claimResolutionSeed().requestedResolution())
                .isEqualTo(catalog.get(1).requestedResolution());
        assertThat(seed.getValue().claimResolutionSeed().requestedAmount())
                .isEqualByComparingTo(catalog.get(1).requestedAmount());
        assertThat(seed.getValue().claimResolutionSeed().originalStatement())
                .isEqualTo(catalog.get(1).originalStatement())
                .isNotEqualTo(catalog.get(1).description());
        assertThat(seed.getValue().respondentAttitudeSeed().respondentRole())
                .isEqualTo("MERCHANT");
        assertThat(seed.getValue().respondentAttitudeSeed().attitude())
                .isEqualTo(catalog.get(1).respondentAttitude());
        assertThat(seed.getValue().respondentAttitudeSeed().source())
                .isEqualTo("发起方单方陈述（主观）");
        assertThat(seed.getValue().respondentAttitudeSeed().confidence())
                .isEqualTo(0.85);
    }

    @Test
    void mapsMerchantInitiatedTemplateClaimAndUserAttitudeIntoTheIntakeSeed() {
        facade.simulateExternalImport(
                command(ActorRole.MERCHANT),
                systemActor(),
                "merchant-seed-contract-key",
                "trace-merchant-seed",
                "request-merchant-seed");

        ArgumentCaptor<IntakeLobbySeed> seed = ArgumentCaptor.forClass(IntakeLobbySeed.class);
        verify(intakeAgentTurnService)
                .startInitialTurn(
                        anyString(),
                        any(AuthenticatedActor.class),
                        seed.capture(),
                        anyString(),
                        anyString());

        SimulatedExternalDisputeTemplate template = catalog.get(1);
        SimulatedExternalDisputeTemplate.InitiatorPerspective merchantPerspective =
                template.forInitiator(ActorRole.MERCHANT);
        assertThat(seed.getValue().claimResolutionSeed().initiatorRole())
                .isEqualTo("MERCHANT");
        assertThat(seed.getValue().claimResolutionSeed().requestedResolution())
                .isEqualTo("VERIFY_OR_EXPLAIN_ONLY")
                .isEqualTo(merchantPerspective.requestedResolution());
        assertThat(seed.getValue().claimResolutionSeed().requestedAmount()).isNull();
        assertThat(seed.getValue().claimResolutionSeed().requestReason())
                .startsWith("我们认为物流已完成签收")
                .doesNotContain("未实际收到商品，希望");
        assertThat(seed.getValue().claimResolutionSeed().originalStatement())
                .startsWith("我们认为物流已完成签收")
                .contains("用户的诉求是：未实际收到商品")
                .doesNotStartWith("物流显示已签收，但我和同住人员");
        assertThat(seed.getValue().respondentAttitudeSeed().respondentRole())
                .isEqualTo("USER");
        assertThat(seed.getValue().respondentAttitudeSeed().attitude())
                .isEqualTo("DISAGREE");
        assertThat(seed.getValue().respondentAttitudeSeed().position())
                .isEqualTo("对方主张：未实际收到商品，希望在核验物流交接链路后退款。");
        assertThat(seed.getValue().respondentAttitudeSeed().position())
                .doesNotContain(template.respondentPosition());
        assertThat(seed.getValue().respondentAttitudeSeed().source())
                .isEqualTo("发起方单方陈述（主观）");
        assertThat(seed.getValue().respondentAttitudeSeed().confidence())
                .isEqualTo(0.85);
    }

    @Test
    void startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits() {
        ArrayDeque<Runnable> queuedSideEffects = new ArrayDeque<>();
        ExternalCaseImportTransactionService deferredTransactionService =
                transactionService(
                        new PostCommitSideEffectExecutor(queuedSideEffects::addLast));
        DisputeImportService deferredFacade =
                new DisputeImportService(
                        deferredTransactionService,
                        new SingleInstanceImportGate());

        TransactionSynchronizationManager.initSynchronization();
        try {
            deferredFacade.simulateExternalImport(
                    command(ActorRole.USER),
                    systemActor(),
                    "deferred-intake-key",
                    "trace-deferred",
                    "request-deferred");

            verifyNoInteractions(intakeAgentTurnService);
            assertThat(queuedSideEffects).isEmpty();

            for (TransactionSynchronization synchronization :
                    TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            assertThat(queuedSideEffects).hasSize(1);
            queuedSideEffects.removeFirst().run();
            verify(intakeAgentTurnService)
                    .startInitialTurn(
                            anyString(),
                            any(AuthenticatedActor.class),
                            any(IntakeLobbySeed.class),
                            anyString(),
                            anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void transactionAndRepositoryDeclareTheRequiredDatabaseLockBoundaries()
            throws NoSuchMethodException {
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
        assertThat(
                        SimulatedImportTemplateCursorRepository.class
                                .getMethod("findByIdForUpdate", String.class)
                                .getAnnotation(Lock.class)
                                .value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private static SimulateExternalImportCommand command(ActorRole initiatorRole) {
        return new SimulateExternalImportCommand(
                1,
                "电商售后争议",
                RiskLevel.MEDIUM,
                initiatorRole,
                initiatorRole == ActorRole.USER ? "user-local" : "merchant-local",
                initiatorRole == ActorRole.USER ? "merchant-local" : "user-local");
    }

    private static AuthenticatedActor systemActor() {
        return new AuthenticatedActor("external-dispute-adapter", ActorRole.SYSTEM);
    }

    private ExternalCaseImportTransactionService transactionService(
            PostCommitSideEffectExecutor postCommit) {
        return new ExternalCaseImportTransactionService(
                caseRepository,
                roomRepository,
                clockRepository,
                participantService,
                intakeAgentTurnService,
                cursorRepository,
                catalog,
                postCommit,
                new DisputeProperties(
                        Duration.ofHours(2),
                        Duration.ofHours(3),
                        Duration.ofMinutes(5),
                        3,
                        Duration.ofSeconds(15),
                        true),
                Clock.fixed(
                        Instant.parse("2026-07-11T00:00:00Z"),
                        ZoneOffset.UTC));
    }
}
