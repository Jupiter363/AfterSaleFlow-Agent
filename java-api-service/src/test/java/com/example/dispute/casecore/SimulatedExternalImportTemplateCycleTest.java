/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证模拟外部导入模板Cycle，覆盖 「importsTemplatesOneThroughTwentyThenCyclesBackToOne」、「replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor」、「mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」、「mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」、「startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits」、「transactionAndRepositoryDeclareTheRequiredDatabaseLockBoundaries」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
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

// 所属模块：【案件核心与导入 / 自动化测试层】类型「SimulatedExternalImportTemplateCycleTest」。
// 类型职责：集中验证模拟外部导入模板Cycle的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「importsTemplatesOneThroughTwentyThenCyclesBackToOne」、「replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor」、「mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」、「mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」、「startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.setUp()」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.setUp()」：在每个测试场景运行前创建「caseRepository.findByCreationIdempotencyKey」、「caseRepository.findBySourceSystemAndExternalCaseRef」、「caseRepository.save」、「roomRepository.findByCaseIdAndRoomType」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.setUp()」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne()」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne()」：复现“核对完整业务行为（场景方法「importsTemplatesOneThroughTwentyThenCyclesBackToOne」）”场景：驱动 「facade.simulateExternalImport」、「item.title」、「item.externalCaseReference」、「titles.subList」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「template-sequence-」、「trace-」、「request-」、「SIM-T」。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne()」守住「案件核心与导入」的可执行规格，尤其防止 「template-sequence-」、「trace-」、「request-」、「SIM-T」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor()」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor()」：复现“回放持久化事件（场景方法「replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor」）”场景：驱动 「facade.simulateExternalImport」、「cursor.getNextTemplateNo」、「replay.id」、「first.id」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「stable-replay-key」、「trace-first」、「request-first」、「trace-replay」。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor()」守住「案件核心与导入」的可执行规格，尤其防止 「stable-replay-key」、「trace-first」、「request-first」、「trace-replay」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」：复现“核对完整业务行为（场景方法「mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」）”场景：驱动 「ArgumentCaptor.forClass」、「facade.simulateExternalImport」、「seed.capture」、「seed.getValue」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「seed-contract-key」、「trace-seed」、「request-seed」、「USER」。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」守住「案件核心与导入」的可执行规格，尤其防止 「seed-contract-key」、「trace-seed」、「request-seed」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude() {
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
        assertThat(seed.getValue().claimResolutionSeed().originalStatement()).isNull();
        assertThat(seed.getValue().respondentAttitudeSeed()).isNull();
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」：复现“核对完整业务行为（场景方法「mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」）”场景：驱动 「ArgumentCaptor.forClass」、「facade.simulateExternalImport」、「seed.capture」、「template.forInitiator」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「merchant-seed-contract-key」、「trace-merchant-seed」、「request-merchant-seed」、「MERCHANT」。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude()」守住「案件核心与导入」的可执行规格，尤其防止 「merchant-seed-contract-key」、「trace-merchant-seed」、「request-merchant-seed」、「MERCHANT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude() {
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
        assertThat(seed.getValue().claimResolutionSeed().originalStatement()).isNull();
        assertThat(seed.getValue().respondentAttitudeSeed()).isNull();
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits()」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits()」：复现“启动下一阶段（场景方法「startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits」）”场景：驱动 「TransactionSynchronizationManager.initSynchronization」、「TransactionSynchronizationManager.getSynchronizations」、「TransactionSynchronizationManager.clearSynchronization」、「deferredFacade.simulateExternalImport」，再用 「verifyNoInteractions」、「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「deferred-intake-key」、「trace-deferred」、「request-deferred」。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits()」的下游是被测服务、仓储或外部客户端替身；「verifyNoInteractions、assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits()」守住「案件核心与导入」的可执行规格，尤其防止 「deferred-intake-key」、「trace-deferred」、「request-deferred」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.transactionAndRepositoryDeclareTheRequiredDatabaseLockBoundaries()」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.transactionAndRepositoryDeclareTheRequiredDatabaseLockBoundaries()」：复现“核对完整业务行为（场景方法「transactionAndRepositoryDeclareTheRequiredDatabaseLockBoundaries」）”场景：驱动 「isNotNull」、「getAnnotation」、「ExternalCaseImportTransactionService.class.getMethod」、「isEqualTo」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「simulateExternalImport」、「findByIdForUpdate」。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.transactionAndRepositoryDeclareTheRequiredDatabaseLockBoundaries()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.transactionAndRepositoryDeclareTheRequiredDatabaseLockBoundaries()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.transactionAndRepositoryDeclareTheRequiredDatabaseLockBoundaries()」守住「案件核心与导入」的可执行规格，尤其防止 「simulateExternalImport」、「findByIdForUpdate」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.command(ActorRole)」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.command(ActorRole)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「SimulateExternalImportCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.command(ActorRole)」由本测试类中的 「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne」、「SimulatedExternalImportTemplateCycleTest.replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor」、「SimulatedExternalImportTemplateCycleTest.mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」、「SimulatedExternalImportTemplateCycleTest.mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」 调用。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.command(ActorRole)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.command(ActorRole)」守住「案件核心与导入」的可执行规格，尤其防止 「电商售后争议」、「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static SimulateExternalImportCommand command(ActorRole initiatorRole) {
        return new SimulateExternalImportCommand(
                1,
                "电商售后争议",
                RiskLevel.MEDIUM,
                initiatorRole,
                initiatorRole == ActorRole.USER ? "user-local" : "merchant-local",
                initiatorRole == ActorRole.USER ? "merchant-local" : "user-local");
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.systemActor()」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.systemActor()」：作为测试辅助方法为“核对完整业务行为（场景方法「systemActor」）”组装或读取「AuthenticatedActor」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.systemActor()」由本测试类中的 「SimulatedExternalImportTemplateCycleTest.importsTemplatesOneThroughTwentyThenCyclesBackToOne」、「SimulatedExternalImportTemplateCycleTest.replaysTheSameCreationKeyWithoutLockingOrAdvancingTheCursor」、「SimulatedExternalImportTemplateCycleTest.mapsUserInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」、「SimulatedExternalImportTemplateCycleTest.mapsMerchantInitiatedTemplateClaimAsFormFactsWithoutInventedStatementsOrAttitude」 调用。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.systemActor()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.systemActor()」守住「案件核心与导入」的可执行规格，尤其防止 「external-dispute-adapter」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AuthenticatedActor systemActor() {
        return new AuthenticatedActor("external-dispute-adapter", ActorRole.SYSTEM);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalImportTemplateCycleTest.transactionService(PostCommitSideEffectExecutor)」。
    // 具体功能：「SimulatedExternalImportTemplateCycleTest.transactionService(PostCommitSideEffectExecutor)」：作为测试辅助方法为“核对完整业务行为（场景方法「transactionService」）”组装或读取「ExternalCaseImportTransactionService」、「DisputeProperties」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「SimulatedExternalImportTemplateCycleTest.transactionService(PostCommitSideEffectExecutor)」由本测试类中的 「SimulatedExternalImportTemplateCycleTest.setUp」、「SimulatedExternalImportTemplateCycleTest.startsTheInitialIntakeOnlyAfterTheCaseTransactionCommits」 调用。
    // 下游影响：「SimulatedExternalImportTemplateCycleTest.transactionService(PostCommitSideEffectExecutor)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「SimulatedExternalImportTemplateCycleTest.transactionService(PostCommitSideEffectExecutor)」守住「案件核心与导入」的可执行规格，尤其防止 「2026-07-11T00:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
                        Duration.ofMinutes(20),
                        Duration.ofSeconds(15),
                        true),
                Clock.fixed(
                        Instant.parse("2026-07-11T00:00:00Z"),
                        ZoneOffset.UTC));
    }
}
