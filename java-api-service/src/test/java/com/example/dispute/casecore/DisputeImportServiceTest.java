/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证争议导入，覆盖 「importsAnExternalDisputeWithOverviewState」、「startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits」、「intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase」、「importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby」、「importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock」、「returnsTheExistingCaseForTheSameExternalReference」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
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

// 所属模块：【案件核心与导入 / 自动化测试层】类型「DisputeImportServiceTest」。
// 类型职责：集中验证争议导入的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「importsAnExternalDisputeWithOverviewState」、「startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits」、「intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase」、「importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby」、「importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.setUp()」。
    // 具体功能：「DisputeImportServiceTest.setUp()」：在每个测试场景运行前创建「Duration.ofHours」、「Duration.ofMinutes」、「Duration.ofSeconds」、「Clock.fixed」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「DisputeImportServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「DisputeImportServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceTest.setUp()」守住「案件核心与导入」的可执行规格，尤其防止 「2026-07-03T00:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.importsAnExternalDisputeWithOverviewState()」。
    // 具体功能：「DisputeImportServiceTest.importsAnExternalDisputeWithOverviewState()」：复现“核对完整业务行为（场景方法「importsAnExternalDisputeWithOverviewState」）”场景：驱动 「repository.findBySourceSystemAndExternalCaseRef」、「repository.save」、「service.importDispute」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「OMS」、「EXT-1001」、「external-adapter」、「import-ext-1001」。
    // 上游调用：「DisputeImportServiceTest.importsAnExternalDisputeWithOverviewState()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.importsAnExternalDisputeWithOverviewState()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.importsAnExternalDisputeWithOverviewState()」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「EXT-1001」、「external-adapter」、「import-ext-1001」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits()」。
    // 具体功能：「DisputeImportServiceTest.startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits()」：复现“启动下一阶段（场景方法「startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits」）”场景：驱动 「repository.findBySourceSystemAndExternalCaseRef」、「repository.save」、「service.importDispute」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「OMS」、「EXT-1003」、「external-adapter」、「import-ext-1003」。
    // 上游调用：「DisputeImportServiceTest.startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits()」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「EXT-1003」、「external-adapter」、「import-ext-1003」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase()」。
    // 具体功能：「DisputeImportServiceTest.intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase()」：复现“核对完整业务行为（场景方法「intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase」）”场景：驱动 「repository.findBySourceSystemAndExternalCaseRef」、「repository.save」、「service.importDispute」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「OMS」、「EXT-POST-COMMIT-FAILURE」、「external-adapter」、「import-ext-post-commit-failure」。
    // 上游调用：「DisputeImportServiceTest.intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase()」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「EXT-POST-COMMIT-FAILURE」、「external-adapter」、「import-ext-post-commit-failure」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby()」。
    // 具体功能：「DisputeImportServiceTest.importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby()」：复现“核对完整业务行为（场景方法「importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby」）”场景：驱动 「repository.findBySourceSystemAndExternalCaseRef」、「repository.save」、「service.importDispute」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「OMS」、「EXT-CLAIM」、「external-adapter」、「import-ext-claim」。
    // 上游调用：「DisputeImportServiceTest.importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby()」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「EXT-CLAIM」、「external-adapter」、「import-ext-claim」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock()」。
    // 具体功能：「DisputeImportServiceTest.importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock()」：复现“核对完整业务行为（场景方法「importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock」）”场景：驱动 「repository.findBySourceSystemAndExternalCaseRef」、「repository.save」、「roomRepository.save」、「clockRepository.save」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「OMS」、「EXT-2001」、「EVIDENCE」、「2026-07-03T02:00:00Z」。
    // 上游调用：「DisputeImportServiceTest.importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock()」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「EXT-2001」、「EVIDENCE」、「2026-07-03T02:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.returnsTheExistingCaseForTheSameExternalReference()」。
    // 具体功能：「DisputeImportServiceTest.returnsTheExistingCaseForTheSameExternalReference()」：复现“返回正确投影（场景方法「returnsTheExistingCaseForTheSameExternalReference」）”场景：驱动 「repository.findBySourceSystemAndExternalCaseRef」、「service.importDispute」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_EXISTING」、「ORDER-1001」、「AFTER-1001」、「LOG-1001」。
    // 上游调用：「DisputeImportServiceTest.returnsTheExistingCaseForTheSameExternalReference()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.returnsTheExistingCaseForTheSameExternalReference()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.returnsTheExistingCaseForTheSameExternalReference()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_EXISTING」、「ORDER-1001」、「AFTER-1001」、「LOG-1001」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.replayRepairsThePersistedRoomInsteadOfTrustingConflictingPayloadState()」。
    // 具体功能：「DisputeImportServiceTest.replayRepairsThePersistedRoomInsteadOfTrustingConflictingPayloadState()」：复现“核对完整业务行为（场景方法「replayRepairsThePersistedRoomInsteadOfTrustingConflictingPayloadState」）”场景：驱动 「repository.findBySourceSystemAndExternalCaseRef」、「roomRepository.save」、「service.importDispute」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_EXISTING_STATE」、「ORDER-1001」、「AFTER-1001」、「LOG-1001」。
    // 上游调用：「DisputeImportServiceTest.replayRepairsThePersistedRoomInsteadOfTrustingConflictingPayloadState()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.replayRepairsThePersistedRoomInsteadOfTrustingConflictingPayloadState()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.replayRepairsThePersistedRoomInsteadOfTrustingConflictingPayloadState()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_EXISTING_STATE」、「ORDER-1001」、「AFTER-1001」、「LOG-1001」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.rejectsReusingAnImportRequestKeyForAnotherExternalCase()」。
    // 具体功能：「DisputeImportServiceTest.rejectsReusingAnImportRequestKeyForAnotherExternalCase()」：复现“拒绝非法输入或越权操作（场景方法「rejectsReusingAnImportRequestKeyForAnotherExternalCase」）”场景：驱动 「repository.findByCreationIdempotencyKey」、「service.importDispute」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_FIRST_IMPORT」、「ORDER-1001」、「AFTER-1001」、「LOG-1001」。
    // 上游调用：「DisputeImportServiceTest.rejectsReusingAnImportRequestKeyForAnotherExternalCase()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.rejectsReusingAnImportRequestKeyForAnotherExternalCase()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.rejectsReusingAnImportRequestKeyForAnotherExternalCase()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_FIRST_IMPORT」、「ORDER-1001」、「AFTER-1001」、「LOG-1001」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.rejectsPartyActorsAtTheInternalImportBoundary()」。
    // 具体功能：「DisputeImportServiceTest.rejectsPartyActorsAtTheInternalImportBoundary()」：复现“拒绝非法输入或越权操作（场景方法「rejectsPartyActorsAtTheInternalImportBoundary」）”场景：驱动 「service.importDispute」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EXT-1002」、「user-local」、「import-ext-1002」。
    // 上游调用：「DisputeImportServiceTest.rejectsPartyActorsAtTheInternalImportBoundary()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.rejectsPartyActorsAtTheInternalImportBoundary()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.rejectsPartyActorsAtTheInternalImportBoundary()」守住「案件核心与导入」的可执行规格，尤其防止 「EXT-1002」、「user-local」、「import-ext-1002」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.simulatedBatchDoesNotOwnATransactionThatAccumulatesItemLocks()」。
    // 具体功能：「DisputeImportServiceTest.simulatedBatchDoesNotOwnATransactionThatAccumulatesItemLocks()」：复现“核对完整业务行为（场景方法「simulatedBatchDoesNotOwnATransactionThatAccumulatesItemLocks」）”场景：驱动 「method.getAnnotation」、「DisputeImportService.class.getMethod」、「isNull」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「simulateExternalImport」。
    // 上游调用：「DisputeImportServiceTest.simulatedBatchDoesNotOwnATransactionThatAccumulatesItemLocks()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.simulatedBatchDoesNotOwnATransactionThatAccumulatesItemLocks()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.simulatedBatchDoesNotOwnATransactionThatAccumulatesItemLocks()」守住「案件核心与导入」的可执行规格，尤其防止 「simulateExternalImport」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.simulatedImportDelegatesToTheTransactionalTemplateBoundary()」。
    // 具体功能：「DisputeImportServiceTest.simulatedImportDelegatesToTheTransactionalTemplateBoundary()」：复现“核对完整业务行为（场景方法「simulatedImportDelegatesToTheTransactionalTemplateBoundary」）”场景：驱动 「transactionalImporter.simulateExternalImport」、「facade.simulateExternalImport」、「mock」、「when」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「merchant-local」、「external-adapter」、「simulate-batch」。
    // 上游调用：「DisputeImportServiceTest.simulatedImportDelegatesToTheTransactionalTemplateBoundary()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.simulatedImportDelegatesToTheTransactionalTemplateBoundary()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.simulatedImportDelegatesToTheTransactionalTemplateBoundary()」守住「案件核心与导入」的可执行规格，尤其防止 「user-local」、「merchant-local」、「external-adapter」、「simulate-batch」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay()」。
    // 具体功能：「DisputeImportServiceTest.simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay()」：复现“核对完整业务行为（场景方法「simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay」）”场景：驱动 「ArgumentCaptor.forClass」、「transactionalImporter.simulateExternalImport」、「facade.simulateExternalImport」、「creationKeys.capture」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「merchant-local」、「external-adapter」、「simulate-retry」。
    // 上游调用：「DisputeImportServiceTest.simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.simulatedImportReusesTheOriginalRequestKeyForTransactionalReplay()」守住「案件核心与导入」的可执行规格，尤其防止 「user-local」、「merchant-local」、「external-adapter」、「simulate-retry」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.directImportsOfDifferentBusinessKeysCannotEnterTheTransactionBoundaryTogether()」。
    // 具体功能：「DisputeImportServiceTest.directImportsOfDifferentBusinessKeysCannotEnterTheTransactionBoundaryTogether()」：复现“核对完整业务行为（场景方法「directImportsOfDifferentBusinessKeysCannotEnterTheTransactionBoundaryTogether」）”场景：驱动 「executor.submit」、「executor.shutdownNow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EXT-GATE-001」、「external-adapter」、「gate-first」、「EXT-GATE-002」。
    // 上游调用：「DisputeImportServiceTest.directImportsOfDifferentBusinessKeysCannotEnterTheTransactionBoundaryTogether()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.directImportsOfDifferentBusinessKeysCannotEnterTheTransactionBoundaryTogether()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.directImportsOfDifferentBusinessKeysCannotEnterTheTransactionBoundaryTogether()」守住「案件核心与导入」的可执行规格，尤其防止 「EXT-GATE-001」、「external-adapter」、「gate-first」、「EXT-GATE-002」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.serializedImportReleasesTheGateAfterFailure()」。
    // 具体功能：「DisputeImportServiceTest.serializedImportReleasesTheGateAfterFailure()」：复现“核对完整业务行为（场景方法「serializedImportReleasesTheGateAfterFailure」）”场景：驱动 「transactionalImporter.importDispute」、「facade.importDispute」、「mock」、「when」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EXT-GATE-FAIL」、「external-adapter」、「gate-failure」、「EXT-GATE-RECOVER」。
    // 上游调用：「DisputeImportServiceTest.serializedImportReleasesTheGateAfterFailure()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.serializedImportReleasesTheGateAfterFailure()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.serializedImportReleasesTheGateAfterFailure()」守住「案件核心与导入」的可执行规格，尤其防止 「EXT-GATE-FAIL」、「external-adapter」、「gate-failure」、「EXT-GATE-RECOVER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.simulationCommandRejectsCountsAboveOne()」。
    // 具体功能：「DisputeImportServiceTest.simulationCommandRejectsCountsAboveOne()」：复现“核对完整业务行为（场景方法「simulationCommandRejectsCountsAboveOne」）”场景：驱动 「hasMessageContaining」、「isInstanceOf」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「merchant-local」。
    // 上游调用：「DisputeImportServiceTest.simulationCommandRejectsCountsAboveOne()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.simulationCommandRejectsCountsAboveOne()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.simulationCommandRejectsCountsAboveOne()」守住「案件核心与导入」的可执行规格，尤其防止 「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.importCommandRejectsNonDemoPartyIds()」。
    // 具体功能：「DisputeImportServiceTest.importCommandRejectsNonDemoPartyIds()」：复现“核对完整业务行为（场景方法「importCommandRejectsNonDemoPartyIds」）”场景：驱动 「hasMessageContaining」、「isInstanceOf」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「OMS」、「EXT-WRONG-PARTY」、「ORDER-WRONG-PARTY」、「user-1」。
    // 上游调用：「DisputeImportServiceTest.importCommandRejectsNonDemoPartyIds()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.importCommandRejectsNonDemoPartyIds()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.importCommandRejectsNonDemoPartyIds()」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「EXT-WRONG-PARTY」、「ORDER-WRONG-PARTY」、「user-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.importCommandRejectsNonDemoMerchantIds()」。
    // 具体功能：「DisputeImportServiceTest.importCommandRejectsNonDemoMerchantIds()」：复现“核对完整业务行为（场景方法「importCommandRejectsNonDemoMerchantIds」）”场景：驱动 「hasMessageContaining」、「isInstanceOf」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「OMS」、「EXT-WRONG-MERCHANT」、「ORDER-WRONG-MERCHANT」、「user-local」。
    // 上游调用：「DisputeImportServiceTest.importCommandRejectsNonDemoMerchantIds()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.importCommandRejectsNonDemoMerchantIds()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.importCommandRejectsNonDemoMerchantIds()」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「EXT-WRONG-MERCHANT」、「ORDER-WRONG-MERCHANT」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.simulationCommandRequiresFixedPartiesInInitiatorOrder()」。
    // 具体功能：「DisputeImportServiceTest.simulationCommandRequiresFixedPartiesInInitiatorOrder()」：复现“核对完整业务行为（场景方法「simulationCommandRequiresFixedPartiesInInitiatorOrder」）”场景：驱动 「hasMessageContaining」、「isInstanceOf」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「merchant-local」、「user-local」。
    // 上游调用：「DisputeImportServiceTest.simulationCommandRequiresFixedPartiesInInitiatorOrder()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeImportServiceTest.simulationCommandRequiresFixedPartiesInInitiatorOrder()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeImportServiceTest.simulationCommandRequiresFixedPartiesInInitiatorOrder()」守住「案件核心与导入」的可执行规格，尤其防止 「merchant-local」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.command(String)」。
    // 具体功能：「DisputeImportServiceTest.command(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「command」，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceTest.command(String)」由本测试类中的 「DisputeImportServiceTest.importsAnExternalDisputeWithOverviewState」、「DisputeImportServiceTest.startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits」、「DisputeImportServiceTest.intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase」、「DisputeImportServiceTest.importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock」 调用。
    // 下游影响：「DisputeImportServiceTest.command(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceTest.command(String)」守住「案件核心与导入」的可执行规格，尤其防止 「INTAKE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static ImportDisputeCommand command(String externalReference) {
        return command(
                externalReference,
                CaseStatus.INTAKE_PENDING,
                "INTAKE",
                null);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.command(String,CaseStatus,String,OffsetDateTime)」。
    // 具体功能：「DisputeImportServiceTest.command(String,CaseStatus,String,OffsetDateTime)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「ImportDisputeCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceTest.command(String,CaseStatus,String,OffsetDateTime)」由本测试类中的 「DisputeImportServiceTest.importsAnExternalDisputeWithOverviewState」、「DisputeImportServiceTest.startsInitialIntakeTurnOnlyAfterTheImportTransactionCommits」、「DisputeImportServiceTest.intakePersistenceFailureAfterCommitDoesNotRollBackTheImportedCase」、「DisputeImportServiceTest.importedEvidenceStateHasAnEnterableRoomAndAuthoritativeClock」 调用。
    // 下游影响：「DisputeImportServiceTest.command(String,CaseStatus,String,OffsetDateTime)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceTest.command(String,CaseStatus,String,OffsetDateTime)」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「ORDER-1001」、「AFTER-1001」、「LOG-1001」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【案件核心与导入 / 自动化测试层】「DisputeImportServiceTest.commandWithClaimSeed(String)」。
    // 具体功能：「DisputeImportServiceTest.commandWithClaimSeed(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「commandWithClaimSeed」）”组装或读取「ImportDisputeCommand」、「ClaimResolutionSeed」、「RespondentAttitudeSeed」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DisputeImportServiceTest.commandWithClaimSeed(String)」由本测试类中的 「DisputeImportServiceTest.importedDisputePassesClaimResolutionSeedIntoTheIntakeLobby」 调用。
    // 下游影响：「DisputeImportServiceTest.commandWithClaimSeed(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DisputeImportServiceTest.commandWithClaimSeed(String)」守住「案件核心与导入」的可执行规格，尤其防止 「OMS」、「ORDER-1001」、「AFTER-1001」、「LOG-1001」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
