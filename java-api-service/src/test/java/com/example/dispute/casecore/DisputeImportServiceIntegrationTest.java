package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ExternalCaseImportTransactionService;
import com.example.dispute.casecore.application.ExternalDisputeSimulationClient;
import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.casecore.application.SingleInstanceImportGate;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.IntakeAgentTurnService;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnableConfigurationProperties(DisputeProperties.class)
@Import({
    DisputeImportService.class,
    ExternalCaseImportTransactionService.class,
    SingleInstanceImportGate.class,
    ParticipantService.class,
    DisputeImportServiceIntegrationTest.FixedClockConfiguration.class,
    DisputeImportServiceIntegrationTest.EmptyLookupBarrierConfiguration.class,
    DisputeImportServiceIntegrationTest.ImportGateObservationConfiguration.class
})
@Testcontainers
class DisputeImportServiceIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_import")
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
                                + "/dispute_import");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private DisputeImportService service;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private CaseRoomRepository roomRepository;
    @Autowired private CaseParticipantRepository participantRepository;
    @Autowired private CasePhaseClockRepository clockRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmptyLookupBarrier emptyLookupBarrier;
    @Autowired private ObservingImportGate observingImportGate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private IntakeAgentTurnService intakeAgentTurnService;
    @MockitoBean private ExternalDisputeSimulationClient simulationClient;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate() {
        observingImportGate.reset();
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

        callerTransaction.executeWithoutResult(
                ignored -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    service.importDispute(
                            command("EXT-CALLER-TRANSACTION"),
                            systemActor(),
                            "caller-transaction-import");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                });

        assertThat(observingImportGate.transactionWasActiveOnEntry()).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport() throws Exception {
        String externalReference = "EXT-CONCURRENT-IMPORT";
        emptyLookupBarrier.coordinateNextPair();
        CyclicBarrier requestStart = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ImportedDisputeView> first =
                    executor.submit(
                            concurrentImport(
                                    requestStart,
                                    externalReference,
                                    "concurrent-import-first"));
            Future<ImportedDisputeView> second =
                    executor.submit(
                            concurrentImport(
                                    requestStart,
                                    externalReference,
                                    "concurrent-import-second"));

            ImportedDisputeView firstResult = get(first);
            ImportedDisputeView secondResult = get(second);

            assertThat(secondResult.id()).isEqualTo(firstResult.id());
            assertThat(
                            jdbcTemplate.queryForObject(
                                    """
                                    select count(*)
                                    from fulfillment_dispute_case
                                    where source_system = ? and external_case_ref = ?
                                    """,
                                    Long.class,
                                    "OMS",
                                    externalReference))
                    .isOne();
            assertThat(roomRepository.findAllByCaseId(firstResult.id())).hasSize(1);
            assertThat(participantRepository.findAllByCaseId(firstResult.id())).hasSize(2);
            verify(intakeAgentTurnService, times(1))
                    .startInitialTurn(
                            any(String.class),
                            any(AuthenticatedActor.class),
                            any(IntakeLobbySeed.class),
                            any(String.class),
                            any(String.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase() {
        String externalReference = "EXT-ROLLBACK-RETRY";
        AtomicReference<String> failedCaseId = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            failedCaseId.set(invocation.getArgument(0));
                            throw new InvalidDataAccessApiUsageException(
                                    "simulated intake persistence failure");
                        })
                .when(intakeAgentTurnService)
                .startInitialTurn(
                        any(String.class),
                        any(AuthenticatedActor.class),
                        any(IntakeLobbySeed.class),
                        any(String.class),
                        any(String.class));

        assertThatThrownBy(
                        () ->
                                service.importDispute(
                                        command(externalReference),
                                        systemActor(),
                                        "import-first-attempt"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessage("simulated intake persistence failure");

        assertThat(
                        caseRepository.findBySourceSystemAndExternalCaseRef(
                                "OMS", externalReference))
                .isEmpty();
        assertThat(roomRepository.findAllByCaseId(failedCaseId.get())).isEmpty();
        assertThat(participantRepository.findAllByCaseId(failedCaseId.get())).isEmpty();
        assertThat(clockRepository.count()).isZero();

        doNothing()
                .when(intakeAgentTurnService)
                .startInitialTurn(
                        any(String.class),
                        any(AuthenticatedActor.class),
                        any(IntakeLobbySeed.class),
                        any(String.class),
                        any(String.class));

        var imported =
                service.importDispute(
                        command(externalReference),
                        systemActor(),
                        "import-retry-success");
        var replayed =
                service.importDispute(
                        command(externalReference),
                        systemActor(),
                        "import-replay-success");

        assertThat(replayed.id()).isEqualTo(imported.id());
        assertThat(
                        caseRepository.findBySourceSystemAndExternalCaseRef(
                                "OMS", externalReference))
                .hasValueSatisfying(saved -> assertThat(saved.getId()).isEqualTo(imported.id()));
        assertThat(roomRepository.findAllByCaseId(imported.id())).hasSize(1);
        assertThat(participantRepository.findAllByCaseId(imported.id())).hasSize(2);
        verify(intakeAgentTurnService, times(2))
                .startInitialTurn(
                        any(String.class),
                        any(AuthenticatedActor.class),
                        any(IntakeLobbySeed.class),
                        any(String.class),
                        any(String.class));
    }

    private Callable<ImportedDisputeView> concurrentImport(
            CyclicBarrier requestStart,
            String externalReference,
            String idempotencyKey) {
        return () -> {
            requestStart.await(5, TimeUnit.SECONDS);
            return service.importDispute(
                    command(externalReference),
                    systemActor(),
                    idempotencyKey);
        };
    }

    private static ImportedDisputeView get(Future<ImportedDisputeView> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static ImportDisputeCommand command(String externalReference) {
        return new ImportDisputeCommand(
                "OMS",
                externalReference,
                "ORDER-ROLLBACK-RETRY",
                "AFTER-ROLLBACK-RETRY",
                "LOG-ROLLBACK-RETRY",
                "user-local",
                "merchant-local",
                "USER",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "用户称物流显示签收但本人未收到包裹。",
                RiskLevel.HIGH,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null);
    }

    private static AuthenticatedActor systemActor() {
        return new AuthenticatedActor("external-dispute-adapter", ActorRole.SYSTEM);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-10T00:00:00Z"),
                    ZoneOffset.UTC);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class EmptyLookupBarrierConfiguration {

        @Bean
        EmptyLookupBarrier emptyLookupBarrier() {
            return new EmptyLookupBarrier();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ImportGateObservationConfiguration {

        @Bean
        @Primary
        ObservingImportGate observingImportGate() {
            return new ObservingImportGate();
        }
    }

    static class ObservingImportGate extends SingleInstanceImportGate {

        private final AtomicBoolean transactionWasActiveOnEntry = new AtomicBoolean();

        @Override
        public <T> T execute(java.util.function.Supplier<T> importAction) {
            transactionWasActiveOnEntry.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            return super.execute(importAction);
        }

        void reset() {
            transactionWasActiveOnEntry.set(false);
        }

        boolean transactionWasActiveOnEntry() {
            return transactionWasActiveOnEntry.get();
        }
    }

    @Aspect
    static class EmptyLookupBarrier {

        private final AtomicReference<CyclicBarrier> barrier = new AtomicReference<>();

        void coordinateNextPair() {
            barrier.set(new CyclicBarrier(2));
        }

        @Around(
                "execution(* com.example.dispute.infrastructure.persistence.repository"
                        + ".FulfillmentCaseRepository.findBySourceSystemAndExternalCaseRef(..))")
        Object awaitOtherEmptyLookup(ProceedingJoinPoint invocation) throws Throwable {
            Object result = invocation.proceed();
            CyclicBarrier active = barrier.get();
            if (active == null
                    || !(result instanceof Optional<?> optional)
                    || optional.isPresent()) {
                return result;
            }
            try {
                active.await(2, TimeUnit.SECONDS);
            } catch (TimeoutException | BrokenBarrierException ignored) {
                // A serialized implementation lets only the winner reach the empty lookup.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } finally {
                if (active.isBroken() || active.getNumberWaiting() == 0) {
                    barrier.compareAndSet(active, null);
                }
            }
            return result;
        }
    }
}
