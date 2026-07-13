/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证争议导入Integration，覆盖 「facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate」、「concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport」、「failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase」、「simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails」、「concurrentTransactionalSimulationsConsumeAdjacentTemplates」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
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
import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplateCatalog;
import com.example.dispute.casecore.application.SingleInstanceImportGate;
import com.example.dispute.casecore.infrastructure.persistence.repository.SimulatedImportTemplateCursorRepository;
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

// 所属模块：【案件核心与导入 / 自动化测试层】类型「DisputeImportServiceIntegrationTest」。
// 类型职责：集中验证争议导入Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate」、「concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport」、「failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase」、「simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails」、「concurrentTransactionalSimulationsConsumeAdjacentTemplates」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnableConfigurationProperties(DisputeProperties.class)
@Import({
    DisputeImportService.class,
    ExternalCaseImportTransactionService.class,
    SingleInstanceImportGate.class,
    SimulatedExternalDisputeTemplateCatalog.class,
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「DisputeImportServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「DisputeImportServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「案件核心与导入」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
    @Autowired private ExternalCaseImportTransactionService transactionService;
    @Autowired private SimulatedImportTemplateCursorRepository cursorRepository;
    @Autowired private CaseRoomRepository roomRepository;
    @Autowired private CaseParticipantRepository participantRepository;
    @Autowired private CasePhaseClockRepository clockRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmptyLookupBarrier emptyLookupBarrier;
    @Autowired private ObservingImportGate observingImportGate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private IntakeAgentTurnService intakeAgentTurnService;

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate()」。
    // 具体功能：「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate()」：复现“核对完整业务行为（场景方法「facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate」）”场景：驱动 「service.importDispute」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EXT-CALLER-TRANSACTION」、「caller-transaction-import」。
    // 上游调用：「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate()」守住「案件核心与导入」的可执行规格，尤其防止 「EXT-CALLER-TRANSACTION」、「caller-transaction-import」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport()」。
    // 具体功能：「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport()」：复现“核对完整业务行为（场景方法「concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport」）”场景：驱动 「executor.submit」、「roomRepository.findAllByCaseId」、「participantRepository.findAllByCaseId」、「executor.shutdownNow」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EXT-CONCURRENT-IMPORT」、「concurrent-import-first」、「concurrent-import-second」、「OMS」。
    // 上游调用：「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport()」守住「案件核心与导入」的可执行规格，尤其防止 「EXT-CONCURRENT-IMPORT」、「concurrent-import-first」、「concurrent-import-second」、「OMS」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase()」。
    // 具体功能：「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase()」：复现“核对完整业务行为（场景方法「failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase」）”场景：驱动 「service.importDispute」、「caseRepository.findBySourceSystemAndExternalCaseRef」、「roomRepository.findAllByCaseId」、「participantRepository.findAllByCaseId」，再用 「assertThatThrownBy」、「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EXT-ROLLBACK-RETRY」、「import-first-attempt」、「OMS」、「import-retry-success」。
    // 上游调用：「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase()」守住「案件核心与导入」的可执行规格，尤其防止 「EXT-ROLLBACK-RETRY」、「import-first-attempt」、「OMS」、「import-retry-success」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails()」。
    // 具体功能：「DisputeImportServiceIntegrationTest.simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails()」：复现“核对完整业务行为（场景方法「simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails」）”场景：驱动 「service.simulateExternalImport」、「caseRepository.findByCreationIdempotencyKey」、「cursorRepository.findById」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「template-rollback-case」、「template-rollback-trace」、「template-rollback-request」、「external-case-template」。
    // 上游调用：「DisputeImportServiceIntegrationTest.simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceIntegrationTest.simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceIntegrationTest.simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails()」守住「案件核心与导入」的可执行规格，尤其防止 「template-rollback-case」、「template-rollback-trace」、「template-rollback-request」、「external-case-template」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails() {
        jdbcTemplate.update(
                "update simulated_import_template_cursor set next_template_no = 1"
                        + " where id = 'external-case-template'");
        String creationKey = "template-rollback-case";
        doAnswer(
                        invocation -> {
                            throw new InvalidDataAccessApiUsageException(
                                    "simulated template intake failure");
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
                                service.simulateExternalImport(
                                        simulationCommand(),
                                        systemActor(),
                                        creationKey,
                                        "template-rollback-trace",
                                        "template-rollback-request"))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessage("simulated template intake failure");

        assertThat(caseRepository.findByCreationIdempotencyKey(creationKey)).isEmpty();
        assertThat(
                        cursorRepository
                                .findById("external-case-template")
                                .orElseThrow()
                                .getNextTemplateNo())
                .isEqualTo(1);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates()」。
    // 具体功能：「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates()」：复现“核对完整业务行为（场景方法「concurrentTransactionalSimulationsConsumeAdjacentTemplates」）”场景：驱动 「executor.submit」、「transactionService.simulateExternalImport」、「cursorRepository.findById」、「executor.shutdownNow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「template-concurrent-first」、「trace-template-first」、「request-template-first」、「template-concurrent-second」。
    // 上游调用：「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates()」守住「案件核心与导入」的可执行规格，尤其防止 「template-concurrent-first」、「trace-template-first」、「request-template-first」、「template-concurrent-second」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentTransactionalSimulationsConsumeAdjacentTemplates() throws Exception {
        jdbcTemplate.update(
                "update simulated_import_template_cursor set next_template_no = 1"
                        + " where id = 'external-case-template'");
        CyclicBarrier requestStart = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first =
                    executor.submit(
                            () -> {
                                requestStart.await(5, TimeUnit.SECONDS);
                                return transactionService
                                        .simulateExternalImport(
                                                simulationCommand(),
                                                systemActor(),
                                                "template-concurrent-first",
                                                "trace-template-first",
                                                "request-template-first")
                                        .items()
                                        .getFirst()
                                        .title();
                            });
            Future<String> second =
                    executor.submit(
                            () -> {
                                requestStart.await(5, TimeUnit.SECONDS);
                                return transactionService
                                        .simulateExternalImport(
                                                simulationCommand(),
                                                systemActor(),
                                                "template-concurrent-second",
                                                "trace-template-second",
                                                "request-template-second")
                                        .items()
                                        .getFirst()
                                        .title();
                            });

            assertThat(java.util.List.of(getTitle(first), getTitle(second)))
                    .containsExactlyInAnyOrder(
                            "物流显示签收但本人未收到",
                            "到货商品破损影响使用");
            assertThat(
                            cursorRepository
                                    .findById("external-case-template")
                                    .orElseThrow()
                                    .getNextTemplateNo())
                    .isEqualTo(3);
        } finally {
            executor.shutdownNow();
        }
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.concurrentImport(CyclicBarrier,String,String)」。
    // 具体功能：「DisputeImportServiceIntegrationTest.concurrentImport(CyclicBarrier,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「concurrentImport」）”组装或读取「service.importDispute」、「requestStart.await」、「command」、「systemActor」，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceIntegrationTest.concurrentImport(CyclicBarrier,String,String)」由本测试类中的 「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport」 调用。
    // 下游影响：「DisputeImportServiceIntegrationTest.concurrentImport(CyclicBarrier,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceIntegrationTest.concurrentImport(CyclicBarrier,String,String)」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.get(Future)」。
    // 具体功能：「DisputeImportServiceIntegrationTest.get(Future)」：作为测试辅助方法为“核对完整业务行为（场景方法「get」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceIntegrationTest.get(Future)」由本测试类中的 「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport」 调用。
    // 下游影响：「DisputeImportServiceIntegrationTest.get(Future)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceIntegrationTest.get(Future)」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static ImportedDisputeView get(Future<ImportedDisputeView> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(10, TimeUnit.SECONDS);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.getTitle(Future)」。
    // 具体功能：「DisputeImportServiceIntegrationTest.getTitle(Future)」：作为测试辅助方法为“核对完整业务行为（场景方法「getTitle」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceIntegrationTest.getTitle(Future)」由本测试类中的 「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates」 调用。
    // 下游影响：「DisputeImportServiceIntegrationTest.getTitle(Future)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceIntegrationTest.getTitle(Future)」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static String getTitle(Future<String> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(10, TimeUnit.SECONDS);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.simulationCommand()」。
    // 具体功能：「DisputeImportServiceIntegrationTest.simulationCommand()」：作为测试辅助方法为“核对完整业务行为（场景方法「simulationCommand」）”组装或读取「SimulateExternalImportCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceIntegrationTest.simulationCommand()」由本测试类中的 「DisputeImportServiceIntegrationTest.simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails」、「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates」 调用。
    // 下游影响：「DisputeImportServiceIntegrationTest.simulationCommand()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceIntegrationTest.simulationCommand()」守住「案件核心与导入」的可执行规格，尤其防止 「电商售后争议」、「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static SimulateExternalImportCommand simulationCommand() {
        return new SimulateExternalImportCommand(
                1,
                "电商售后争议",
                RiskLevel.MEDIUM,
                ActorRole.USER,
                "user-local",
                "merchant-local");
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.command(String)」。
    // 具体功能：「DisputeImportServiceIntegrationTest.command(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「ImportDisputeCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceIntegrationTest.command(String)」由本测试类中的 「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate」、「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase」、「DisputeImportServiceIntegrationTest.concurrentImport」 调用。
    // 下游影响：「DisputeImportServiceIntegrationTest.command(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceIntegrationTest.command(String)」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「ORDER-ROLLBACK-RETRY」、「AFTER-ROLLBACK-RETRY」、「LOG-ROLLBACK-RETRY」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.systemActor()」。
    // 具体功能：「DisputeImportServiceIntegrationTest.systemActor()」：作为测试辅助方法为“核对完整业务行为（场景方法「systemActor」）”组装或读取「AuthenticatedActor」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceIntegrationTest.systemActor()」由本测试类中的 「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate」、「DisputeImportServiceIntegrationTest.failedInitialTurnRollsBackTheImportAndRetryCreatesExactlyOneCase」、「DisputeImportServiceIntegrationTest.simulatedImportRollsBackTheCaseAndTemplateCursorWhenIntakeFails」、「DisputeImportServiceIntegrationTest.concurrentTransactionalSimulationsConsumeAdjacentTemplates」 调用。
    // 下游影响：「DisputeImportServiceIntegrationTest.systemActor()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceIntegrationTest.systemActor()」守住「案件核心与导入」的可执行规格，尤其防止 「external-dispute-adapter」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AuthenticatedActor systemActor() {
        return new AuthenticatedActor("external-dispute-adapter", ActorRole.SYSTEM);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】类型「FixedClockConfiguration」。
    // 类型职责：在 Spring 启动期装配Fixed时钟所需 Bean 和基础设施参数；本类型显式提供 「fixedClock」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.FixedClockConfiguration.fixedClock()」。
        // 具体功能：「DisputeImportServiceIntegrationTest.FixedClockConfiguration.fixedClock()」：作为测试辅助方法为“核对完整业务行为（场景方法「fixedClock」）”组装或读取「Clock.fixed」、「Instant.parse」，供本测试类的场景方法复用。
        // 上游调用：「DisputeImportServiceIntegrationTest.FixedClockConfiguration.fixedClock()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DisputeImportServiceIntegrationTest.FixedClockConfiguration.fixedClock()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「DisputeImportServiceIntegrationTest.FixedClockConfiguration.fixedClock()」守住「案件核心与导入」的可执行规格，尤其防止 「2026-07-10T00:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-10T00:00:00Z"),
                    ZoneOffset.UTC);
        }
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】类型「EmptyLookupBarrierConfiguration」。
    // 类型职责：在 Spring 启动期装配为空LookupBarrier所需 Bean 和基础设施参数；本类型显式提供 「emptyLookupBarrier」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class EmptyLookupBarrierConfiguration {

        // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.EmptyLookupBarrierConfiguration.emptyLookupBarrier()」。
        // 具体功能：「DisputeImportServiceIntegrationTest.EmptyLookupBarrierConfiguration.emptyLookupBarrier()」：作为测试辅助方法为“核对完整业务行为（场景方法「emptyLookupBarrier」）”组装或读取「EmptyLookupBarrier」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「DisputeImportServiceIntegrationTest.EmptyLookupBarrierConfiguration.emptyLookupBarrier()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DisputeImportServiceIntegrationTest.EmptyLookupBarrierConfiguration.emptyLookupBarrier()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「DisputeImportServiceIntegrationTest.EmptyLookupBarrierConfiguration.emptyLookupBarrier()」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        EmptyLookupBarrier emptyLookupBarrier() {
            return new EmptyLookupBarrier();
        }
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】类型「ImportGateObservationConfiguration」。
    // 类型职责：在 Spring 启动期装配导入门禁Observation所需 Bean 和基础设施参数；本类型显式提供 「observingImportGate」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration(proxyBeanMethods = false)
    static class ImportGateObservationConfiguration {

        // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.ImportGateObservationConfiguration.observingImportGate()」。
        // 具体功能：「DisputeImportServiceIntegrationTest.ImportGateObservationConfiguration.observingImportGate()」：作为测试辅助方法为“核对完整业务行为（场景方法「observingImportGate」）”组装或读取「ObservingImportGate」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「DisputeImportServiceIntegrationTest.ImportGateObservationConfiguration.observingImportGate()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DisputeImportServiceIntegrationTest.ImportGateObservationConfiguration.observingImportGate()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「DisputeImportServiceIntegrationTest.ImportGateObservationConfiguration.observingImportGate()」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        @Primary
        ObservingImportGate observingImportGate() {
            return new ObservingImportGate();
        }
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】类型「ObservingImportGate」。
    // 类型职责：承载Observing导入门禁在当前业务模块中的规则与协作边界；本类型显式提供 「execute」、「reset」、「transactionWasActiveOnEntry」。
    // 协作关系：主要由 「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate」 使用。
    // 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    static class ObservingImportGate extends SingleInstanceImportGate {

        private final AtomicBoolean transactionWasActiveOnEntry = new AtomicBoolean();

        // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.ObservingImportGate.execute(Supplier)」。
        // 具体功能：「DisputeImportServiceIntegrationTest.ObservingImportGate.execute(Supplier)」：作为「ObservingImportGate」测试替身实现「execute」：返回预设值 「super.execute(importAction)」；记录 「TransactionSynchronizationManager.isActualTransactionActive」、「super.execute」 的输入或调用次数，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「DisputeImportServiceIntegrationTest.ObservingImportGate.execute(Supplier)」由本测试类中的 「ObservingImportGate.execute」 调用。
        // 下游影响：「DisputeImportServiceIntegrationTest.ObservingImportGate.execute(Supplier)」下游仅修改测试内存状态或返回桩值：返回预设值 「super.execute(importAction)」；记录 「TransactionSynchronizationManager.isActualTransactionActive」、「super.execute」 的输入或调用次数；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「DisputeImportServiceIntegrationTest.ObservingImportGate.execute(Supplier)」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public <T> T execute(java.util.function.Supplier<T> importAction) {
            transactionWasActiveOnEntry.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            return super.execute(importAction);
        }

        // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.ObservingImportGate.reset()」。
        // 具体功能：「DisputeImportServiceIntegrationTest.ObservingImportGate.reset()」：作为测试辅助方法为“核对完整业务行为（场景方法「reset」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
        // 上游调用：「DisputeImportServiceIntegrationTest.ObservingImportGate.reset()」由本测试类中的 「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate」 调用。
        // 下游影响：「DisputeImportServiceIntegrationTest.ObservingImportGate.reset()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「DisputeImportServiceIntegrationTest.ObservingImportGate.reset()」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        void reset() {
            transactionWasActiveOnEntry.set(false);
        }

        // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.ObservingImportGate.transactionWasActiveOnEntry()」。
        // 具体功能：「DisputeImportServiceIntegrationTest.ObservingImportGate.transactionWasActiveOnEntry()」：作为测试辅助方法为“核对完整业务行为（场景方法「transactionWasActiveOnEntry」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
        // 上游调用：「DisputeImportServiceIntegrationTest.ObservingImportGate.transactionWasActiveOnEntry()」由本测试类中的 「DisputeImportServiceIntegrationTest.facadeSuspendsCallerTransactionBeforeAcquiringTheImportGate」 调用。
        // 下游影响：「DisputeImportServiceIntegrationTest.ObservingImportGate.transactionWasActiveOnEntry()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「DisputeImportServiceIntegrationTest.ObservingImportGate.transactionWasActiveOnEntry()」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        boolean transactionWasActiveOnEntry() {
            return transactionWasActiveOnEntry.get();
        }
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】类型「EmptyLookupBarrier」。
    // 类型职责：承载为空LookupBarrier在当前业务模块中的规则与协作边界；本类型显式提供 「coordinateNextPair」、「awaitOtherEmptyLookup」。
    // 协作关系：主要由 「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport」 使用。
    // 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @Aspect
    static class EmptyLookupBarrier {

        private final AtomicReference<CyclicBarrier> barrier = new AtomicReference<>();

        // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.coordinateNextPair()」。
        // 具体功能：「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.coordinateNextPair()」：作为测试辅助方法为“核对完整业务行为（场景方法「coordinateNextPair」）”组装或读取「CyclicBarrier」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.coordinateNextPair()」由本测试类中的 「DisputeImportServiceIntegrationTest.concurrentImportsOfTheSameExternalCaseReturnOneAtomicImport」 调用。
        // 下游影响：「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.coordinateNextPair()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.coordinateNextPair()」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        void coordinateNextPair() {
            barrier.set(new CyclicBarrier(2));
        }

        // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.awaitOtherEmptyLookup(ProceedingJoinPoint)」。
        // 具体功能：「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.awaitOtherEmptyLookup(ProceedingJoinPoint)」：作为测试辅助方法为“核对完整业务行为（场景方法「awaitOtherEmptyLookup」）”组装或读取「Thread.currentThread」、「invocation.proceed」、「optional.isPresent」、「active.await」，供本测试类的场景方法复用。
        // 上游调用：「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.awaitOtherEmptyLookup(ProceedingJoinPoint)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.awaitOtherEmptyLookup(ProceedingJoinPoint)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「DisputeImportServiceIntegrationTest.EmptyLookupBarrier.awaitOtherEmptyLookup(ProceedingJoinPoint)」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
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
