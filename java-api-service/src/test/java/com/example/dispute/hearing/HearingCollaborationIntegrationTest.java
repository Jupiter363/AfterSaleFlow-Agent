/*
 * 所属模块：共享小法庭。
 * 文件职责：验证庭审CollaborationIntegration，覆盖 「onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」、「theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow」、「factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound」、「openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap」、「hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」、「completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;

import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.HearingStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.evidence.application.EvidenceDossierRevisionService;
import com.example.dispute.hearing.application.CompleteHearingRoundCommand;
import com.example.dispute.hearing.application.AgentA2ACommand;
import com.example.dispute.hearing.application.AgentA2AMessageService;
import com.example.dispute.hearing.application.HearingCourtOrchestrator;
import com.example.dispute.hearing.application.HearingFinalDraftService;
import com.example.dispute.hearing.application.HearingOutcomeOrchestrationService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import com.example.dispute.hearing.application.HearingStatusView;
import com.example.dispute.hearing.application.SettlementProposalCommand;
import com.example.dispute.hearing.application.SettlementService;
import com.example.dispute.hearing.application.SettlementVersionConflictException;
import com.example.dispute.hearing.application.SubmitHearingRoundCommand;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.hearing.domain.HearingStopReason;
import com.example.dispute.hearing.domain.SettlementStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementConfirmationRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementProposalRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.PostReviewOrchestrationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.workflow.application.HearingAgentClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【共享小法庭 / 自动化测试层】类型「HearingCollaborationIntegrationTest」。
// 类型职责：集中验证庭审CollaborationIntegration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「setUp」、「onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」、「theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow」、「factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound」、「openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnableConfigurationProperties(DisputeProperties.class)
@Import({
    SettlementService.class,
    HearingRoundService.class,
    HearingFinalDraftService.class,
    HearingOutcomeOrchestrationService.class,
    RemedyApplicationService.class,
    ReviewApplicationService.class,
    AgentA2AMessageService.class,
    EvidenceDossierRevisionService.class,
    NotificationService.class,
    CaseEventService.class,
    HearingCollaborationIntegrationTest.FixedClockConfiguration.class
})
@Testcontainers
class HearingCollaborationIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_hearing")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「HearingCollaborationIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「HearingCollaborationIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「HearingCollaborationIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingCollaborationIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「共享小法庭」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_hearing");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private SettlementService settlementService;
    @Autowired private HearingRoundService roundService;
    @Autowired private AgentA2AMessageService a2aMessageService;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private CaseRoomRepository roomRepository;
    @Autowired private SettlementProposalRepository proposalRepository;
    @Autowired private SettlementConfirmationRepository confirmationRepository;
    @Autowired private HearingRoundRepository roundRepository;
    @Autowired private HearingRoundPartySubmissionRepository submissionRepository;
    @Autowired private CaseTimelineEventRepository eventRepository;
    @Autowired private HearingStateRepository hearingStateRepository;
    @Autowired private AdjudicationDraftRepository draftRepository;
    @Autowired private EvidenceDossierRepository evidenceDossierRepository;
    @Autowired private RemedyPlanRepository remedyPlanRepository;
    @Autowired private ReviewPacketRepository reviewPacketRepository;
    @Autowired private ReviewTaskRepository reviewTaskRepository;
    @Autowired private MutableClock mutableClock;
    @MockitoBean private HearingWorkflowCoordinator hearingWorkflowCoordinator;
    @MockitoBean private HearingCourtOrchestrator hearingCourtOrchestrator;
    @MockitoBean private HearingAgentClient hearingAgentClient;
    @MockitoBean private AccessSessionResolver accessSessionResolver;
    @MockitoBean private SessionPermissionService sessionPermissionService;
    @MockitoBean private AuditRecorder auditRecorder;
    @MockitoBean private CaseLifecycleNotificationService lifecycleNotifications;
    @MockitoBean private PostReviewOrchestrationService postReviewOrchestration;

    private AuthenticatedActor user;
    private AuthenticatedActor merchant;
    private AuthenticatedActor system;

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.setUp()」。
    // 具体功能：「HearingCollaborationIntegrationTest.setUp()」：在每个测试场景运行前创建「Instant.parse」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「HearingCollaborationIntegrationTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「HearingCollaborationIntegrationTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingCollaborationIntegrationTest.setUp()」守住「共享小法庭」的可执行规格，尤其防止 「user-local」、「merchant-local」、「hearing-controller」、「2026-07-03T01:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        user = new AuthenticatedActor("user-local", ActorRole.USER);
        merchant = new AuthenticatedActor("merchant-local", ActorRole.MERCHANT);
        system = new AuthenticatedActor("hearing-controller", ActorRole.SYSTEM);
        mutableClock.set(Instant.parse("2026-07-03T01:00:00Z"));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge()」。
    // 具体功能：「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge()」：复现“核对完整业务行为（场景方法「onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」）”场景：驱动 「settlementService.propose」、「settlementService.confirm」、「proposalRepository.findAllByCaseIdOrderByProposalVersionDesc」、「confirmationRepository.count」，再用 「assertThat」、「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_SETTLEMENT」、「{\"refund\":50}」、「TRACE_v1」、「confirm-v1-user」。
    // 上游调用：「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge()」的下游是被测服务、仓储或外部客户端替身；「assertThat、assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_SETTLEMENT」、「{\"refund\":50}」、「TRACE_v1」、「confirm-v1-user」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void onlyBothConfirmationsOnTheCurrentSettlementVersionConverge() {
        seedHearing("CASE_SETTLEMENT");

        var v1 =
                settlementService.propose(
                        "CASE_SETTLEMENT",
                        new SettlementProposalCommand("退款 50 元", "{\"refund\":50}"),
                        merchant,
                        "TRACE_v1");
        settlementService.confirm(
                "CASE_SETTLEMENT", v1.version(), user, "confirm-v1-user");
        var v2 =
                settlementService.propose(
                        "CASE_SETTLEMENT",
                        new SettlementProposalCommand("退款 80 元", "{\"refund\":80}"),
                        merchant,
                        "TRACE_v2");

        assertThat(
                        settlementService
                                .get("CASE_SETTLEMENT", v1.version(), user)
                                .status())
                .isEqualTo(SettlementStatus.SUPERSEDED);
        assertThatThrownBy(
                        () ->
                                settlementService.confirm(
                                        "CASE_SETTLEMENT",
                                        v1.version(),
                                        merchant,
                                        "confirm-v1-merchant"))
                .isInstanceOf(SettlementVersionConflictException.class);
        assertThatThrownBy(
                        () ->
                                settlementService.confirm(
                                        "CASE_SETTLEMENT",
                                        v2.version(),
                                        user,
                                        "confirm-v1-user"))
                .isInstanceOf(IdempotencyConflictException.class);

        settlementService.confirm(
                "CASE_SETTLEMENT", v2.version(), user, "confirm-v2-user");
        var confirmed =
                settlementService.confirm(
                        "CASE_SETTLEMENT",
                        v2.version(),
                        merchant,
                        "confirm-v2-merchant");
        var replayedFinalConfirmation =
                settlementService.confirm(
                        "CASE_SETTLEMENT",
                        v2.version(),
                        merchant,
                        "confirm-v2-merchant");

        assertThat(confirmed.status()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(replayedFinalConfirmation.status())
                .isEqualTo(SettlementStatus.CONFIRMED);
        assertThatThrownBy(
                        () ->
                                settlementService.confirm(
                                        "CASE_SETTLEMENT",
                                        v2.version(),
                                        user,
                                        "confirm-v2-merchant"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(confirmed.confirmedRoles())
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);
        assertThat(proposalRepository.findAllByCaseIdOrderByProposalVersionDesc(
                        "CASE_SETTLEMENT"))
                .hasSize(2);
        assertThat(confirmationRepository.count()).isEqualTo(3);
        assertThat(
                        eventRepository
                                .findByCaseIdAndEventKey(
                                        "CASE_SETTLEMENT",
                                        "settlement-confirmed:2")
                                .orElseThrow()
                                .getEventType())
                .isEqualTo("SETTLEMENT_CONFIRMED");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow()」。
    // 具体功能：「HearingCollaborationIntegrationTest.theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow()」：复现“核对完整业务行为（场景方法「theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow」）”场景：驱动 「roundService.completeNext」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_THREE_ROUNDS」、「{\"round\":1}」、「{\"round\":2}」、「{\"round\":3}」。
    // 上游调用：「HearingCollaborationIntegrationTest.theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_THREE_ROUNDS」、「{\"round\":1}」、「{\"round\":2}」、「{\"round\":3}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow() {
        seedHearing("CASE_THREE_ROUNDS");

        var first =
                roundService.completeNext(
                        "CASE_THREE_ROUNDS",
                        new CompleteHearingRoundCommand(1, "{\"round\":1}", false),
                        system);
        var second =
                roundService.completeNext(
                        "CASE_THREE_ROUNDS",
                        new CompleteHearingRoundCommand(1, "{\"round\":2}", false),
                        system);
        var third =
                roundService.completeNext(
                        "CASE_THREE_ROUNDS",
                        new CompleteHearingRoundCommand(1, "{\"round\":3}", false),
                        system);

        assertThat(first.stopReason()).isNull();
        assertThat(second.stopReason()).isNull();
        assertThat(third.stopReason()).isEqualTo(HearingStopReason.MAX_ROUNDS);
        assertThat(third.status()).isEqualTo(HearingRoundStatus.FORCED_CLOSED);
        assertThat(roundRepository.findAllByCaseIdOrderByRoundNoAsc("CASE_THREE_ROUNDS"))
                .hasSize(3);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound()」。
    // 具体功能：「HearingCollaborationIntegrationTest.factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound()」：复现“核对完整业务行为（场景方法「factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound」）”场景：驱动 「roundService.completeNext」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_FACTS_HINT」、「{\"round\":1}」。
    // 上游调用：「HearingCollaborationIntegrationTest.factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_FACTS_HINT」、「{\"round\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound() {
        seedHearing("CASE_FACTS_HINT");

        var first =
                roundService.completeNext(
                        "CASE_FACTS_HINT",
                        new CompleteHearingRoundCommand(1, "{\"round\":1}", true),
                        system);

        assertThat(first.stopReason()).isNull();
        assertThat(first.status()).isEqualTo(HearingRoundStatus.COMPLETED);
        completeCourtOrchestration("CASE_FACTS_HINT", 1, false);
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_FACTS_HINT", 1, false);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap()」。
    // 具体功能：「HearingCollaborationIntegrationTest.openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap()」：复现“核对完整业务行为（场景方法「openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap」）”场景：驱动 「roundService.ensureInitialRoundOpen」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」、「eventRepository.findByCaseIdAndEventKey」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_OPENING」、「hearing-controller」、「TRACE_HEARING_ROUND_1」、「hearing-round-opened:1」。
    // 上游调用：「HearingCollaborationIntegrationTest.openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_OPENING」、「hearing-controller」、「TRACE_HEARING_ROUND_1」、「hearing-round-opened:1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap() {
        seedHearing("CASE_OPENING");

        var first =
                roundService.ensureInitialRoundOpen(
                        "CASE_OPENING",
                        2,
                        "hearing-controller");
        var replayed =
                roundService.ensureInitialRoundOpen(
                        "CASE_OPENING",
                        2,
                        "hearing-controller");

        assertThat(first.roundNo()).isEqualTo(1);
        assertThat(first.status()).isEqualTo(HearingRoundStatus.OPEN);
        assertThat(first.submittedRoles()).isEmpty();
        assertThat(replayed.roundId()).isEqualTo(first.roundId());
        assertThat(roundRepository.findAllByCaseIdOrderByRoundNoAsc("CASE_OPENING"))
                .hasSize(1);
        verify(hearingCourtOrchestrator, never())
                .afterRoundOpenedAfterCommit(
                        "CASE_OPENING", 1, "TRACE_HEARING_ROUND_1");
        assertThat(
                        eventRepository
                                .findByCaseIdAndEventKey(
                                        "CASE_OPENING",
                                        "hearing-round-opened:1")
                                .orElseThrow()
                                .getEventType())
                .isEqualTo("HEARING_ROUND_OPENED");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness()」。
    // 具体功能：「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness()」：复现“核对完整业务行为（场景方法「hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness」）”场景：驱动 「roundService.ensureInitialRoundOpen」、「roundService.status」、「roundService.recordPartyMessageSubmission」、「roundService.submitParty」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_STATUS_VIEW」、「hearing-controller」、「ROUND_OPEN」、「本轮陈述中」。
    // 上游调用：「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_STATUS_VIEW」、「hearing-controller」、「ROUND_OPEN」、「本轮陈述中」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness() {
        seedHearing("CASE_STATUS_VIEW");
        roundService.ensureInitialRoundOpen(
                "CASE_STATUS_VIEW",
                2,
                "hearing-controller");

        HearingStatusView open = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(open.hearingPhase()).isEqualTo("ROUND_OPEN");
        assertThat(open.phaseLabel()).isEqualTo("本轮陈述中");
        assertThat(open.canCompleteHearing()).isFalse();
        assertThat(open.reviewGateReady()).isFalse();
        assertThat(open.currentRoundNo()).isEqualTo(1);
        assertThat(open.roundStage()).isEqualTo("FACT_STATEMENT");
        assertThat(open.nextStepHint()).contains("完成本轮陈述");

        roundService.recordPartyMessageSubmission(
                "CASE_STATUS_VIEW",
                1,
                "MESSAGE_USER_STATUS",
                "用户说明签收记录与本人实际收货情况不一致，请核验签收人身份。",
                user);
        roundService.recordPartyMessageSubmission(
                "CASE_STATUS_VIEW",
                1,
                "MESSAGE_MERCHANT_STATUS",
                "商家说明订单已发货并有物流签收记录，请核验签收底单。",
                merchant);

        HearingStatusView secondRoundOpen = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(secondRoundOpen.hearingPhase()).isEqualTo("ROUND_OPEN");
        assertThat(secondRoundOpen.phaseLabel()).isEqualTo("本轮陈述中");
        assertThat(secondRoundOpen.canCompleteHearing()).isFalse();
        assertThat(secondRoundOpen.currentRoundNo()).isEqualTo(2);
        assertThat(secondRoundOpen.roundStage()).isEqualTo("EVIDENCE_EXPLANATION");
        assertThat(secondRoundOpen.nextStepHint()).contains("完成本轮陈述");

        roundService.submitParty(
                "CASE_STATUS_VIEW",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"USER\",\"statement\":\"第二轮：用户解释补充证据与签收争议的关系。\"}"),
                user);
        roundService.submitParty(
                "CASE_STATUS_VIEW",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"MERCHANT\",\"statement\":\"第二轮：商家解释物流签收凭证和发货记录。\"}"),
                merchant);
        roundService.submitParty(
                "CASE_STATUS_VIEW",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"USER\",\"statement\":\"第三轮：用户说明不认可仅凭签收记录驳回退款。\"}"),
                user);
        roundService.submitParty(
                "CASE_STATUS_VIEW",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"MERCHANT\",\"statement\":\"第三轮：商家请求按物流签收记录维持不退款。\"}"),
                merchant);

        HearingStatusView waitingDraft = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(waitingDraft.hearingPhase()).isEqualTo("JUDGE_DRAFTING");
        assertThat(waitingDraft.phaseLabel()).isEqualTo("等待裁决草案");
        assertThat(waitingDraft.finalRoundSealed()).isTrue();
        assertThat(waitingDraft.canCompleteHearing()).isFalse();
        assertThat(waitingDraft.currentRoundNo()).isEqualTo(3);
        assertThat(waitingDraft.roundStage()).isEqualTo("REMEDY_CONFIRMATION");
        assertThat(waitingDraft.nextStepHint()).contains("方案确认");
        assertThat(waitingDraft.nextStepHint()).contains("确认或说明异议");
        assertThat(waitingDraft.nextStepHint()).contains("等待 AI 法官生成裁决草案");

        HearingStateEntity hearing =
                hearingStateRepository.saveAndFlush(
                        HearingStateEntity.start(
                                "HEARING_CASE_STATUS_VIEW",
                                "CASE_STATUS_VIEW",
                                "hearing-window-CASE_STATUS_VIEW",
                                "temporal-worker"));
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        "DRAFT_CASE_STATUS_VIEW",
                        "CASE_STATUS_VIEW",
                        hearing.getId(),
                        4,
                        "[\"物流显示签收，但用户称本人未收到包裹\"]",
                        "[\"签收凭证仍需核验签收人身份\"]",
                        "[\"按平台签收争议规则进入人工审核确认\"]",
                        "[\"审核员需核验物流签收底单\"]",
                        "SIGNATURE_PROOF_REVIEW_REQUIRED",
                        new BigDecimal("0.7600"),
                        "AI 法官已基于三轮陈述和证据卷宗生成裁决草案，仍需平台审核员确认。",
                        "python-agent-service/presiding-judge",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));

        HearingStatusView draftReady = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(draftReady.hearingPhase()).isEqualTo("DRAFT_READY");
        assertThat(draftReady.phaseLabel()).isEqualTo("裁决草案已生成");
        assertThat(draftReady.canCompleteHearing()).isTrue();
        assertThat(draftReady.latestDraftId()).isEqualTo("DRAFT_CASE_STATUS_VIEW");
        assertThat(draftReady.nextStepHint()).contains("进入结果页查看草案说明");

        remedyPlanRepository.saveAndFlush(
                RemedyPlanEntity.pendingApproval(
                        "PLAN_CASE_STATUS_VIEW",
                        "CASE_STATUS_VIEW",
                        "DRAFT_CASE_STATUS_VIEW",
                        1,
                        RouteType.SIMPLE_HEARING,
                        RiskLevel.HIGH,
                        "[]",
                        "[]",
                        "[]",
                        "review-orchestrator"));
        reviewPacketRepository.saveAndFlush(
                ReviewPacketEntity.create(
                        "PACKET_CASE_STATUS_VIEW",
                        "CASE_STATUS_VIEW",
                        "PLAN_CASE_STATUS_VIEW",
                        1,
                        "{}",
                        "[]",
                        "[]",
                        "[]",
                        "{}",
                        "{}",
                        "[]",
                        "review-orchestrator"));
        reviewTaskRepository.saveAndFlush(
                ReviewTaskEntity.pending(
                        "REVIEW_CASE_STATUS_VIEW",
                        "CASE_STATUS_VIEW",
                        "PLAN_CASE_STATUS_VIEW",
                        "PACKET_CASE_STATUS_VIEW",
                        "NORMAL",
                        "PLATFORM_REVIEWER",
                        OffsetDateTime.parse("2026-07-04T04:00:00Z"),
                        "review-orchestrator"));

        HearingStatusView reviewGateReady = roundService.status("CASE_STATUS_VIEW", user);

        assertThat(reviewGateReady.hearingPhase()).isEqualTo("REVIEW_GATE_READY");
        assertThat(reviewGateReady.phaseLabel()).isEqualTo("裁决草案已生成");
        assertThat(reviewGateReady.nextStepHint()).contains("进入结果页查看草案说明");
        assertThat(reviewGateReady.nextStepHint()).doesNotContain("平台审核入口");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting()」。
    // 具体功能：「HearingCollaborationIntegrationTest.completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting()」：复现“核对完整业务行为（场景方法「completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting」）”场景：驱动 「hearingStateRepository.saveAndFlush」、「roundService.completeNext」、「roundService.completeHearing」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」，再用 「assertThat」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_TEMPORAL_FINAL_DRAFT_OWNER」、「HEARING_CASE_TEMPORAL_FINAL_DRAFT_OWNER」、「hearing-bootstrap」、「{\"round\":1}」。
    // 上游调用：「HearingCollaborationIntegrationTest.completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_TEMPORAL_FINAL_DRAFT_OWNER」、「HEARING_CASE_TEMPORAL_FINAL_DRAFT_OWNER」、「hearing-bootstrap」、「{\"round\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting() {
        seedHearing("CASE_TEMPORAL_FINAL_DRAFT_OWNER");
        hearingStateRepository.saveAndFlush(
                HearingStateEntity.start(
                        "HEARING_CASE_TEMPORAL_FINAL_DRAFT_OWNER",
                        "CASE_TEMPORAL_FINAL_DRAFT_OWNER",
                        "hearing-window-CASE_TEMPORAL_FINAL_DRAFT_OWNER",
                        "hearing-bootstrap"));
        attachHearingWorkflow("CASE_TEMPORAL_FINAL_DRAFT_OWNER");
        roundService.completeNext(
                "CASE_TEMPORAL_FINAL_DRAFT_OWNER",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);
        roundService.completeNext(
                "CASE_TEMPORAL_FINAL_DRAFT_OWNER",
                new CompleteHearingRoundCommand(2, "{\"round\":2}", false),
                system);
        roundService.completeNext(
                "CASE_TEMPORAL_FINAL_DRAFT_OWNER",
                new CompleteHearingRoundCommand(2, "{\"round\":3}", false),
                system);

        HearingStatusView completed =
                roundService.completeHearing("CASE_TEMPORAL_FINAL_DRAFT_OWNER", user);

        assertThat(completed.hearingPhase()).isEqualTo("JUDGE_DRAFTING");
        assertThat(completed.latestDraftId()).isNull();
        assertThat(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(
                        "CASE_TEMPORAL_FINAL_DRAFT_OWNER"))
                .isEmpty();
        verifyNoInteractions(hearingAgentClient);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce()」。
    // 具体功能：「HearingCollaborationIntegrationTest.completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce()」：复现“核对完整业务行为（场景方法「completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce」）”场景：驱动 「hearingStateRepository.saveAndFlush」、「roundService.completeNext」、「roundService.status」、「roundService.completeHearing」，再用 「assertThat」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_FINAL_DRAFT_COMPLETION」、「HEARING_CASE_FINAL_DRAFT_COMPLETION」、「hearing-bootstrap」、「{\"round\":1}」。
    // 上游调用：「HearingCollaborationIntegrationTest.completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_FINAL_DRAFT_COMPLETION」、「HEARING_CASE_FINAL_DRAFT_COMPLETION」、「hearing-bootstrap」、「{\"round\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce() {
        seedHearing("CASE_FINAL_DRAFT_COMPLETION");
        hearingStateRepository.saveAndFlush(
                HearingStateEntity.start(
                        "HEARING_CASE_FINAL_DRAFT_COMPLETION",
                        "CASE_FINAL_DRAFT_COMPLETION",
                        "hearing-window-CASE_FINAL_DRAFT_COMPLETION",
                        "hearing-bootstrap"));
        attachHearingWorkflow("CASE_FINAL_DRAFT_COMPLETION");
        roundService.completeNext(
                "CASE_FINAL_DRAFT_COMPLETION",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_COMPLETION",
                new CompleteHearingRoundCommand(2, "{\"round\":2}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_COMPLETION",
                new CompleteHearingRoundCommand(2, "{\"round\":3}", false),
                system);

        HearingStatusView waitingDraft = roundService.status("CASE_FINAL_DRAFT_COMPLETION", user);
        assertThat(waitingDraft.hearingPhase()).isEqualTo("JUDGE_DRAFTING");
        assertThat(waitingDraft.canCompleteHearing()).isFalse();

        seedTemporalFinalDraft("CASE_FINAL_DRAFT_COMPLETION");
        HearingStatusView completed = roundService.completeHearing("CASE_FINAL_DRAFT_COMPLETION", user);

        assertThat(completed.canCompleteHearing()).isTrue();
        assertThat(completed.hearingPhase()).isEqualTo("REVIEW_GATE_READY");
        assertThat(completed.latestDraftId()).isNotBlank();
        assertThat(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(
                        "CASE_FINAL_DRAFT_COMPLETION"))
                .hasValueSatisfying(
                        draft -> {
                            assertThat(draft.getId()).isEqualTo(completed.latestDraftId());
                            assertThat(draft.getDraftStatus()).isEqualTo("PENDING_HUMAN_REVIEW");
                            assertThat(draft.getDraftText()).contains("裁决草案");
                        });
        assertThat(
                        hearingStateRepository
                                .findByCaseId("CASE_FINAL_DRAFT_COMPLETION")
                                .orElseThrow()
                                .getHearingStatus()
                                .name())
                .isEqualTo("COMPLETED");
        assertThat(
                        eventRepository
                                .findByCaseIdAndEventKey(
                                        "CASE_FINAL_DRAFT_COMPLETION",
                                        "hearing-phase-changed:REVIEW_GATE_READY:"
                                                + completed.latestDraftId())
                                .orElseThrow())
                .satisfies(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo("HEARING_PHASE_CHANGED");
                            assertThat(event.getEventJson()).contains("\"hearing_phase\":\"REVIEW_GATE_READY\"");
                            assertThat(event.getEventJson())
                                    .contains("\"latest_draft_id\":\"" + completed.latestDraftId() + "\"");
                            assertThat(event.getEventJson())
                                    .contains("\"review_task_id\":\"" + completed.reviewTaskId() + "\"");
                        });
        verifyNoInteractions(hearingAgentClient);

        HearingStatusView replay = roundService.completeHearing("CASE_FINAL_DRAFT_COMPLETION", user);

        assertThat(replay.latestDraftId()).isEqualTo(completed.latestDraftId());
        assertThat(draftRepository.findAll()).filteredOn(
                        draft -> "CASE_FINAL_DRAFT_COMPLETION".equals(draft.getCaseId()))
                .hasSize(1);
        verifyNoInteractions(hearingAgentClient);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.completeHearingSynchronouslyCreatesReviewGateForImmediateReviewConfirmation()」。
    // 具体功能：「HearingCollaborationIntegrationTest.completeHearingSynchronouslyCreatesReviewGateForImmediateReviewConfirmation()」：复现“核对完整业务行为（场景方法「completeHearingSynchronouslyCreatesReviewGateForImmediateReviewConfirmation」）”场景：驱动 「hearingStateRepository.saveAndFlush」、「roundService.completeNext」、「roundService.completeHearing」、「reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_FINAL_REVIEW_GATE_SYNC」、「HEARING_CASE_FINAL_REVIEW_GATE_SYNC」、「hearing-bootstrap」、「{\"round\":1}」。
    // 上游调用：「HearingCollaborationIntegrationTest.completeHearingSynchronouslyCreatesReviewGateForImmediateReviewConfirmation()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.completeHearingSynchronouslyCreatesReviewGateForImmediateReviewConfirmation()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.completeHearingSynchronouslyCreatesReviewGateForImmediateReviewConfirmation()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_FINAL_REVIEW_GATE_SYNC」、「HEARING_CASE_FINAL_REVIEW_GATE_SYNC」、「hearing-bootstrap」、「{\"round\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void completeHearingSynchronouslyCreatesReviewGateForImmediateReviewConfirmation() {
        seedHearing("CASE_FINAL_REVIEW_GATE_SYNC");
        hearingStateRepository.saveAndFlush(
                HearingStateEntity.start(
                        "HEARING_CASE_FINAL_REVIEW_GATE_SYNC",
                        "CASE_FINAL_REVIEW_GATE_SYNC",
                        "hearing-window-CASE_FINAL_REVIEW_GATE_SYNC",
                        "hearing-bootstrap"));
        attachHearingWorkflow("CASE_FINAL_REVIEW_GATE_SYNC");
        roundService.completeNext(
                "CASE_FINAL_REVIEW_GATE_SYNC",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_REVIEW_GATE_SYNC",
                new CompleteHearingRoundCommand(2, "{\"round\":2}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_REVIEW_GATE_SYNC",
                new CompleteHearingRoundCommand(2, "{\"round\":3}", false),
                system);

        seedTemporalFinalDraft("CASE_FINAL_REVIEW_GATE_SYNC");
        HearingStatusView completed = roundService.completeHearing("CASE_FINAL_REVIEW_GATE_SYNC", user);

        assertThat(completed.hearingPhase()).isEqualTo("REVIEW_GATE_READY");
        assertThat(completed.reviewGateReady()).isTrue();
        assertThat(completed.reviewTaskId()).isNotBlank();
        assertThat(reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(
                        "CASE_FINAL_REVIEW_GATE_SYNC"))
                .hasValueSatisfying(
                        task -> assertThat(task.getId()).isEqualTo(completed.reviewTaskId()));
        assertThat(remedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(
                        "CASE_FINAL_REVIEW_GATE_SYNC"))
                .isPresent();
        assertThat(reviewPacketRepository.findAll())
                .filteredOn(packet -> "CASE_FINAL_REVIEW_GATE_SYNC".equals(packet.getCaseId()))
                .hasSize(1);
        assertThat(caseRepository.findById("CASE_FINAL_REVIEW_GATE_SYNC"))
                .hasValueSatisfying(
                        dispute ->
                                assertThat(dispute.getCaseStatus())
                                        .isEqualTo(CaseStatus.WAITING_HUMAN_REVIEW));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.completeHearingRecoversDraftReadyCaseWithoutWaitingForScheduler()」。
    // 具体功能：「HearingCollaborationIntegrationTest.completeHearingRecoversDraftReadyCaseWithoutWaitingForScheduler()」：复现“核对完整业务行为（场景方法「completeHearingRecoversDraftReadyCaseWithoutWaitingForScheduler」）”场景：驱动 「hearingStateRepository.saveAndFlush」、「roundService.completeNext」、「draftRepository.saveAndFlush」、「roundService.status」，再用 「assertThat」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_FINAL_REVIEW_GATE_DRAFT_READY」、「HEARING_CASE_FINAL_REVIEW_GATE_DRAFT_READY」、「hearing-bootstrap」、「{\"round\":1}」。
    // 上游调用：「HearingCollaborationIntegrationTest.completeHearingRecoversDraftReadyCaseWithoutWaitingForScheduler()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.completeHearingRecoversDraftReadyCaseWithoutWaitingForScheduler()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.completeHearingRecoversDraftReadyCaseWithoutWaitingForScheduler()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_FINAL_REVIEW_GATE_DRAFT_READY」、「HEARING_CASE_FINAL_REVIEW_GATE_DRAFT_READY」、「hearing-bootstrap」、「{\"round\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void completeHearingRecoversDraftReadyCaseWithoutWaitingForScheduler() {
        seedHearing("CASE_FINAL_REVIEW_GATE_DRAFT_READY");
        HearingStateEntity hearing =
                hearingStateRepository.saveAndFlush(
                        HearingStateEntity.start(
                                "HEARING_CASE_FINAL_REVIEW_GATE_DRAFT_READY",
                                "CASE_FINAL_REVIEW_GATE_DRAFT_READY",
                                "hearing-window-CASE_FINAL_REVIEW_GATE_DRAFT_READY",
                                "hearing-bootstrap"));
        attachHearingWorkflow("CASE_FINAL_REVIEW_GATE_DRAFT_READY");
        roundService.completeNext(
                "CASE_FINAL_REVIEW_GATE_DRAFT_READY",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_REVIEW_GATE_DRAFT_READY",
                new CompleteHearingRoundCommand(2, "{\"round\":2}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_REVIEW_GATE_DRAFT_READY",
                new CompleteHearingRoundCommand(2, "{\"round\":3}", false),
                system);
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        "DRAFT_CASE_FINAL_REVIEW_GATE_DRAFT_READY",
                        "CASE_FINAL_REVIEW_GATE_DRAFT_READY",
                        hearing.getId(),
                        4,
                        "[]",
                        "[]",
                        "[]",
                        "[]",
                        "MANUAL_REVIEW",
                        new BigDecimal("0.2500"),
                        "已生成最终草案，但庭审状态尚未完成。",
                        "python-agent-service/presiding-judge",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));

        HearingStatusView draftReady =
                roundService.status("CASE_FINAL_REVIEW_GATE_DRAFT_READY", user);
        assertThat(draftReady.hearingPhase()).isEqualTo("DRAFT_READY");
        assertThat(draftReady.finalRoundSealed()).isTrue();
        assertThat(draftReady.currentRoundNo()).isEqualTo(3);
        assertThat(hearingStateRepository.findByCaseId("CASE_FINAL_REVIEW_GATE_DRAFT_READY"))
                .hasValueSatisfying(
                        state -> assertThat(state.getHearingStatus()).isEqualTo(HearingStatus.RUNNING));
        assertThat(reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(
                        "CASE_FINAL_REVIEW_GATE_DRAFT_READY"))
                .isEmpty();
        clearInvocations(hearingAgentClient);

        HearingStatusView completed =
                roundService.completeHearing("CASE_FINAL_REVIEW_GATE_DRAFT_READY", user);

        assertThat(completed.hearingPhase()).isEqualTo("REVIEW_GATE_READY");
        assertThat(completed.reviewTaskId()).isNotBlank();
        assertThat(hearingStateRepository.findByCaseId("CASE_FINAL_REVIEW_GATE_DRAFT_READY"))
                .hasValueSatisfying(
                        state ->
                                assertThat(state.getHearingStatus())
                                        .isEqualTo(HearingStatus.COMPLETED));
        verifyNoInteractions(hearingAgentClient);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport()」。
    // 具体功能：「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport()」：复现“核对完整业务行为（场景方法「seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport」）”场景：驱动 「hearingStateRepository.saveAndFlush」、「roundService.completeNext」、「a2aMessageService.record」、「roundService.completeHearing」，再用 「assertThat」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_FINAL_DRAFT_JURY_REPORT」、「HEARING_CASE_FINAL_DRAFT_JURY_REPORT」、「hearing-bootstrap」、「{\"round\":1}」。
    // 上游调用：「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_FINAL_DRAFT_JURY_REPORT」、「HEARING_CASE_FINAL_DRAFT_JURY_REPORT」、「hearing-bootstrap」、「{\"round\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport() {
        seedHearing("CASE_FINAL_DRAFT_JURY_REPORT");
        hearingStateRepository.saveAndFlush(
                HearingStateEntity.start(
                        "HEARING_CASE_FINAL_DRAFT_JURY_REPORT",
                        "CASE_FINAL_DRAFT_JURY_REPORT",
                        "hearing-window-CASE_FINAL_DRAFT_JURY_REPORT",
                        "hearing-bootstrap"));
        attachHearingWorkflow("CASE_FINAL_DRAFT_JURY_REPORT");
        roundService.completeNext(
                "CASE_FINAL_DRAFT_JURY_REPORT",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_JURY_REPORT",
                new CompleteHearingRoundCommand(2, "{\"round\":2}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_JURY_REPORT",
                new CompleteHearingRoundCommand(2, "{\"round\":3}", false),
                system);
        a2aMessageService.record(
                new AgentA2ACommand(
                        "CASE_FINAL_DRAFT_JURY_REPORT",
                        3,
                        "JURY_PANEL",
                        AgentA2AMessageService.PRESIDING_JUDGE,
                        "JURY_REVIEW_REPORT",
                        Map.of(
                                "round_no",
                                3,
                                "review_focus_signal",
                                List.of("用户要求复核签收人身份。")),
                        Map.of(
                                "summary",
                                "评审团认为签收人身份和签收地点仍需重点核对。",
                                "risk_level",
                                "MEDIUM",
                                "confidence_score",
                                75,
                                "review_focus_signal",
                                List.of("用户要求复核签收人身份。")),
                        "REVIEWER_VISIBLE",
                        "RUN_JURY_REPORT_3"));

        seedTemporalFinalDraft("CASE_FINAL_DRAFT_JURY_REPORT");
        HearingStatusView completed =
                roundService.completeHearing("CASE_FINAL_DRAFT_JURY_REPORT", user);

        assertThat(completed.hearingPhase()).isEqualTo("REVIEW_GATE_READY");
        assertThat(a2aMessageService.findFormalJuryReviewReport(
                        "CASE_FINAL_DRAFT_JURY_REPORT", 3))
                .hasValueSatisfying(
                        report ->
                                assertThat(report.payloadJson())
                                        .contains("签收人身份和签收地点"));
        verifyNoInteractions(hearingAgentClient);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.finalRoundRequiresAFinalDraftVersionInsteadOfReusingEarlierAnalysisDrafts()」。
    // 具体功能：「HearingCollaborationIntegrationTest.finalRoundRequiresAFinalDraftVersionInsteadOfReusingEarlierAnalysisDrafts()」：复现“核对完整业务行为（场景方法「finalRoundRequiresAFinalDraftVersionInsteadOfReusingEarlierAnalysisDrafts」）”场景：驱动 「hearingStateRepository.saveAndFlush」、「draftRepository.saveAndFlush」、「roundService.completeNext」、「roundService.status」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_FINAL_DRAFT_VERSION」、「HEARING_CASE_FINAL_DRAFT_VERSION」、「hearing-bootstrap」、「DRAFT_CASE_FINAL_DRAFT_VERSION_ROUND_1」。
    // 上游调用：「HearingCollaborationIntegrationTest.finalRoundRequiresAFinalDraftVersionInsteadOfReusingEarlierAnalysisDrafts()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.finalRoundRequiresAFinalDraftVersionInsteadOfReusingEarlierAnalysisDrafts()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.finalRoundRequiresAFinalDraftVersionInsteadOfReusingEarlierAnalysisDrafts()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_FINAL_DRAFT_VERSION」、「HEARING_CASE_FINAL_DRAFT_VERSION」、「hearing-bootstrap」、「DRAFT_CASE_FINAL_DRAFT_VERSION_ROUND_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void finalRoundRequiresAFinalDraftVersionInsteadOfReusingEarlierAnalysisDrafts() {
        seedHearing("CASE_FINAL_DRAFT_VERSION");
        HearingStateEntity hearing =
                hearingStateRepository.saveAndFlush(
                        HearingStateEntity.start(
                                "HEARING_CASE_FINAL_DRAFT_VERSION",
                                "CASE_FINAL_DRAFT_VERSION",
                                "hearing-window-CASE_FINAL_DRAFT_VERSION",
                                "hearing-bootstrap"));
        attachHearingWorkflow("CASE_FINAL_DRAFT_VERSION");
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        "DRAFT_CASE_FINAL_DRAFT_VERSION_ROUND_1",
                        "CASE_FINAL_DRAFT_VERSION",
                        hearing.getId(),
                        2,
                        "[]",
                        "[]",
                        "[]",
                        "[\"早期轮次分析草案，不应作为最终裁决草案\"]",
                        "EARLY_ANALYSIS_ONLY",
                        new BigDecimal("0.5000"),
                        "这是第 1 轮后的阶段性分析草案。",
                        "python-agent-service/presiding-judge",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));
        roundService.completeNext(
                "CASE_FINAL_DRAFT_VERSION",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_VERSION",
                new CompleteHearingRoundCommand(2, "{\"round\":2}", false),
                system);
        roundService.completeNext(
                "CASE_FINAL_DRAFT_VERSION",
                new CompleteHearingRoundCommand(2, "{\"round\":3}", false),
                system);

        HearingStatusView waitingDraft = roundService.status("CASE_FINAL_DRAFT_VERSION", user);

        assertThat(waitingDraft.hearingPhase()).isEqualTo("JUDGE_DRAFTING");
        assertThat(waitingDraft.latestDraftId()).isNull();
        assertThat(waitingDraft.canCompleteHearing()).isFalse();

        seedTemporalFinalDraft("CASE_FINAL_DRAFT_VERSION");
        HearingStatusView completed = roundService.completeHearing("CASE_FINAL_DRAFT_VERSION", user);

        assertThat(completed.canCompleteHearing()).isTrue();
        assertThat(completed.latestDraftId()).isNotEqualTo("DRAFT_CASE_FINAL_DRAFT_VERSION_ROUND_1");
        assertThat(
                        draftRepository
                                .findFirstByCaseIdOrderByDraftVersionDesc(
                                        "CASE_FINAL_DRAFT_VERSION")
                                .orElseThrow()
                                .getDraftVersion())
                .isEqualTo(4);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.workflowSignalWaitsUntilCourtOrchestrationCompletes()」。
    // 具体功能：「HearingCollaborationIntegrationTest.workflowSignalWaitsUntilCourtOrchestrationCompletes()」：复现“核对完整业务行为（场景方法「workflowSignalWaitsUntilCourtOrchestrationCompletes」）”场景：驱动 「roundService.completeNext」，再用 「verify」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_COURT_SIGNAL_ORDER」、「{\"round\":1}」。
    // 上游调用：「HearingCollaborationIntegrationTest.workflowSignalWaitsUntilCourtOrchestrationCompletes()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.workflowSignalWaitsUntilCourtOrchestrationCompletes()」的下游是被测服务、仓储或外部客户端替身；「verify、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.workflowSignalWaitsUntilCourtOrchestrationCompletes()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_COURT_SIGNAL_ORDER」、「{\"round\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void workflowSignalWaitsUntilCourtOrchestrationCompletes() {
        seedHearing("CASE_COURT_SIGNAL_ORDER");
        attachHearingWorkflow("CASE_COURT_SIGNAL_ORDER");

        roundService.completeNext(
                "CASE_COURT_SIGNAL_ORDER",
                new CompleteHearingRoundCommand(2, "{\"round\":1}", false),
                system);

        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(hearingCourtOrchestrator)
                .afterRoundClosedAfterCommit(
                        org.mockito.ArgumentMatchers.eq("CASE_COURT_SIGNAL_ORDER"),
                        org.mockito.ArgumentMatchers.eq(1),
                        org.mockito.ArgumentMatchers.eq(false),
                        org.mockito.ArgumentMatchers.any(),
                        completion.capture());
        verifyNoInteractions(hearingWorkflowCoordinator);

        completion.getValue().run();

        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_COURT_SIGNAL_ORDER", 1, false);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge()」。
    // 具体功能：「HearingCollaborationIntegrationTest.partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge()」：复现“核对完整业务行为（场景方法「partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge」）”场景：驱动 「roundService.submitParty」、「roundRepository.findByCaseIdAndRoundNo」，再用 「assertThat」、「verifyNoInteractions」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_PARTY_ROUND」、「BOTH_PARTIES_SUBMITTED」、「双方本轮陈述已提交并封存」、「鍙」。
    // 上游调用：「HearingCollaborationIntegrationTest.partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_PARTY_ROUND」、「BOTH_PARTIES_SUBMITTED」、「双方本轮陈述已提交并封存」、「鍙」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge() {
        seedHearing("CASE_PARTY_ROUND");

        var userSubmission =
                roundService.submitParty(
                        "CASE_PARTY_ROUND",
                        new SubmitHearingRoundCommand(
                                2,
                                "{\"party\":\"USER\",\"statement\":\"我已经完成本轮陈述\"}"),
                        user);

        assertThat(userSubmission.status()).isEqualTo(HearingRoundStatus.WAITING);
        assertThat(userSubmission.roundNo()).isEqualTo(1);
        assertThat(userSubmission.submittedRoles()).containsExactly(ActorRole.USER);
        assertThat(userSubmission.roundDeadlineAt()).isNotNull();
        verifyNoInteractions(hearingWorkflowCoordinator);

        var merchantSubmission =
                roundService.submitParty(
                        "CASE_PARTY_ROUND",
                        new SubmitHearingRoundCommand(
                                2,
                                "{\"party\":\"MERCHANT\",\"statement\":\"商家也完成本轮答辩\"}"),
                        merchant);

        assertThat(merchantSubmission.roundNo()).isEqualTo(2);
        assertThat(merchantSubmission.status()).isEqualTo(HearingRoundStatus.OPEN);
        assertThat(merchantSubmission.submittedRoles()).isEmpty();
        var completedRound =
                roundRepository
                        .findByCaseIdAndRoundNo("CASE_PARTY_ROUND", 1)
                        .orElseThrow();
        assertThat(completedRound.getRoundStatus())
                .isEqualTo(HearingRoundStatus.COMPLETED);
        assertThat(completedRound.getSummaryJson())
                .contains("BOTH_PARTIES_SUBMITTED")
                .contains("双方本轮陈述已提交并封存")
                .doesNotContain("鍙");
        assertThat(completedRound.getSummaryJson())
                .contains("双方本轮陈述已提交并封存")
                .doesNotContain("�")
                .doesNotContain("鍙")
                .doesNotContain("鏈");
        completeCourtOrchestration("CASE_PARTY_ROUND", 1, false);
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_PARTY_ROUND", 1, false);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound()」。
    // 具体功能：「HearingCollaborationIntegrationTest.bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound()」：复现“核对完整业务行为（场景方法「bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound」）”场景：驱动 「roundService.submitParty」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_PARTY_ROUND_CONTINUES」、「roundNo」、「roundStatus」。
    // 上游调用：「HearingCollaborationIntegrationTest.bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_PARTY_ROUND_CONTINUES」、「roundNo」、「roundStatus」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound() {
        seedHearing("CASE_PARTY_ROUND_CONTINUES");

        roundService.submitParty(
                "CASE_PARTY_ROUND_CONTINUES",
                new SubmitHearingRoundCommand(
                        2,
                        "{\"party\":\"USER\",\"statement\":\"first user statement\"}"),
                user);

        var nextRound =
                roundService.submitParty(
                        "CASE_PARTY_ROUND_CONTINUES",
                        new SubmitHearingRoundCommand(
                                2,
                                "{\"party\":\"MERCHANT\",\"statement\":\"first merchant answer\"}"),
                        merchant);

        assertThat(nextRound.roundNo()).isEqualTo(2);
        assertThat(nextRound.status()).isEqualTo(HearingRoundStatus.OPEN);
        assertThat(nextRound.submittedRoles()).isEmpty();
        assertThat(roundRepository.findAllByCaseIdOrderByRoundNoAsc(
                        "CASE_PARTY_ROUND_CONTINUES"))
                .extracting("roundNo", "roundStatus")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1, HearingRoundStatus.COMPLETED),
                        org.assertj.core.groups.Tuple.tuple(
                                2, HearingRoundStatus.OPEN));
        completeCourtOrchestration("CASE_PARTY_ROUND_CONTINUES", 1, false);
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_PARTY_ROUND_CONTINUES", 1, false);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce()」。
    // 具体功能：「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce()」：复现“核对完整业务行为（场景方法「dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce」）”场景：驱动 「roundService.submitParty」、「roundService.expireDueRounds」、「roundService.list」、「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」，再用 「assertThat」、「verifyNoInteractions」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_ROUND_TIMEOUT」、「2026-07-03T01:05:01Z」、「ROUND_DEADLINE_EXPIRED」、「AUTO_TIMEOUT」。
    // 上游调用：「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_ROUND_TIMEOUT」、「2026-07-03T01:05:01Z」、「ROUND_DEADLINE_EXPIRED」、「AUTO_TIMEOUT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce() {
        seedHearing("CASE_ROUND_TIMEOUT");

        var userSubmission =
                roundService.submitParty(
                        "CASE_ROUND_TIMEOUT",
                        new SubmitHearingRoundCommand(
                                2,
                                "{\"party\":\"USER\",\"statement\":\"ready\"}"),
                        user);

        assertThat(userSubmission.status()).isEqualTo(HearingRoundStatus.WAITING);
        verifyNoInteractions(hearingWorkflowCoordinator);

        mutableClock.set(Instant.parse("2026-07-03T01:05:01Z"));
        int expired = roundService.expireDueRounds();

        assertThat(expired).isEqualTo(1);
        var closedRound = roundService.list("CASE_ROUND_TIMEOUT", user).get(0);
        assertThat(closedRound.status()).isEqualTo(HearingRoundStatus.COMPLETED);
        assertThat(closedRound.submittedRoles())
                .containsExactlyInAnyOrder(ActorRole.USER, ActorRole.MERCHANT);
        assertThat(closedRound.summaryJson())
                .contains("ROUND_DEADLINE_EXPIRED")
                .contains("AUTO_TIMEOUT")
                .contains("本轮提交时效已届满")
                .doesNotContain("鏈");
        assertThat(
                        submissionRepository
                                .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                        "CASE_ROUND_TIMEOUT", 1))
                .extracting("submissionSource")
                .containsExactlyInAnyOrder(
                        HearingRoundSubmissionSource.PARTY_ACTION,
                        HearingRoundSubmissionSource.AUTO_TIMEOUT);
        completeCourtOrchestration("CASE_ROUND_TIMEOUT", 1, false);
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_ROUND_TIMEOUT", 1, false);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.roomMessageStatementsFromBothPartiesCloseRoundAndOpenNextRound()」。
    // 具体功能：「HearingCollaborationIntegrationTest.roomMessageStatementsFromBothPartiesCloseRoundAndOpenNextRound()」：复现“核对完整业务行为（场景方法「roomMessageStatementsFromBothPartiesCloseRoundAndOpenNextRound」）”场景：驱动 「roundService.ensureInitialRoundOpen」、「roundService.recordPartyMessageSubmission」、「roundRepository.findByCaseIdAndRoundNo」、「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」，再用 「assertThat」、「verifyNoInteractions」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_ROOM_STATEMENT」、「hearing-controller」、「MESSAGE_USER_HEARING_TEXT」、「用户补充：请核验签收人身份和投递照片。」。
    // 上游调用：「HearingCollaborationIntegrationTest.roomMessageStatementsFromBothPartiesCloseRoundAndOpenNextRound()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.roomMessageStatementsFromBothPartiesCloseRoundAndOpenNextRound()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.roomMessageStatementsFromBothPartiesCloseRoundAndOpenNextRound()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_ROOM_STATEMENT」、「hearing-controller」、「MESSAGE_USER_HEARING_TEXT」、「用户补充：请核验签收人身份和投递照片。」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void roomMessageStatementsFromBothPartiesCloseRoundAndOpenNextRound() {
        seedHearing("CASE_ROOM_STATEMENT");
        roundService.ensureInitialRoundOpen(
                "CASE_ROOM_STATEMENT",
                2,
                "hearing-controller");

        var afterUserMessage =
                roundService.recordPartyMessageSubmission(
                        "CASE_ROOM_STATEMENT",
                        1,
                        "MESSAGE_USER_HEARING_TEXT",
                        "用户补充：请核验签收人身份和投递照片。",
                        user);

        assertThat(afterUserMessage.status()).isEqualTo(HearingRoundStatus.WAITING);
        assertThat(afterUserMessage.submittedRoles()).containsExactly(ActorRole.USER);
        assertThat(afterUserMessage.currentActorSubmitted()).isTrue();
        assertThat(
                        roundRepository
                                .findByCaseIdAndRoundNo("CASE_ROOM_STATEMENT", 1)
                                .orElseThrow()
                                .getRoundStatus())
                .isEqualTo(HearingRoundStatus.WAITING);
        verifyNoInteractions(hearingWorkflowCoordinator);

        var afterMerchantMessage =
                roundService.recordPartyMessageSubmission(
                        "CASE_ROOM_STATEMENT",
                        1,
                        "MESSAGE_MERCHANT_HEARING_TEXT",
                        "商家补充：需要物流公司出具签收底单和投递照片。",
                        merchant);

        assertThat(afterMerchantMessage.roundNo()).isEqualTo(2);
        assertThat(afterMerchantMessage.status()).isEqualTo(HearingRoundStatus.OPEN);
        assertThat(afterMerchantMessage.submittedRoles()).isEmpty();
        var closedRound =
                roundRepository
                        .findByCaseIdAndRoundNo("CASE_ROOM_STATEMENT", 1)
                        .orElseThrow();
        assertThat(closedRound.getRoundStatus()).isEqualTo(HearingRoundStatus.COMPLETED);
        assertThat(closedRound.getSummaryJson())
                .contains("BOTH_PARTIES_SUBMITTED")
                .contains("MESSAGE_USER_HEARING_TEXT")
                .contains("MESSAGE_MERCHANT_HEARING_TEXT")
                .doesNotContain("AUTO_TIMEOUT");
        assertThat(
                        submissionRepository
                                .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                                        "CASE_ROOM_STATEMENT", 1))
                .extracting("submissionSource")
                .containsExactlyInAnyOrder(
                        HearingRoundSubmissionSource.PARTY_ACTION,
                        HearingRoundSubmissionSource.PARTY_ACTION);
        completeCourtOrchestration("CASE_ROOM_STATEMENT", 1, false);
        verify(hearingWorkflowCoordinator)
                .roundCompletedAfterCommit("CASE_ROOM_STATEMENT", 1, false);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion()」。
    // 具体功能：「HearingCollaborationIntegrationTest.secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion()」：复现“核对完整业务行为（场景方法「secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion」）”场景：驱动 「evidenceDossierRepository.saveAndFlush」、「roundService.ensureInitialRoundOpen」、「roundService.submitParty」、「evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_EVIDENCE_REVISION」、「EVIDENCE_DOSSIER_REVISION_V1」、「evidence-clerk」、「{\"evidence_count\":1}」。
    // 上游调用：「HearingCollaborationIntegrationTest.secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_EVIDENCE_REVISION」、「EVIDENCE_DOSSIER_REVISION_V1」、「evidence-clerk」、「{\"evidence_count\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion() {
        seedHearing("CASE_EVIDENCE_REVISION");
        evidenceDossierRepository.saveAndFlush(
                EvidenceDossierEntity.frozen(
                        "EVIDENCE_DOSSIER_REVISION_V1",
                        "CASE_EVIDENCE_REVISION",
                        1,
                        "evidence-clerk",
                        "{\"evidence_count\":1}",
                        "[]",
                        """
                        {
                          "fact_evidence_matrix": [
                            {
                              "fact_id": "FACT_SIGNED",
                              "fact": "物流显示包裹已签收",
                              "supporting_evidence": ["EVD_MERCHANT_LOGISTICS"],
                              "opposing_evidence": [],
                              "evidence_strength": "MEDIUM"
                            }
                          ],
                          "handoff_notes": "开庭基线证据矩阵"
                        }
                        """));

        roundService.ensureInitialRoundOpen(
                "CASE_EVIDENCE_REVISION",
                1,
                "hearing-controller");
        roundService.submitParty(
                "CASE_EVIDENCE_REVISION",
                new SubmitHearingRoundCommand(
                        1,
                        "{\"statement\":\"用户称物流显示签收，但本人没有收到包裹。\"}"),
                user);
        roundService.submitParty(
                "CASE_EVIDENCE_REVISION",
                new SubmitHearingRoundCommand(
                        1,
                        "{\"statement\":\"商家称已按订单地址发货，物流系统显示签收。\"}"),
                merchant);

        roundService.submitParty(
                "CASE_EVIDENCE_REVISION",
                new SubmitHearingRoundCommand(
                        1,
                        "{\"statement\":\"用户说明现有证据主要用于证明未实际收到包裹，请核验签收人身份。\"}"),
                user);
        roundService.submitParty(
                "CASE_EVIDENCE_REVISION",
                new SubmitHearingRoundCommand(
                        1,
                        "{\"statement\":\"商家说明物流签收记录来自承运商系统，暂未补充签收照片。\"}"),
                merchant);

        EvidenceDossierEntity active =
                evidenceDossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc("CASE_EVIDENCE_REVISION")
                        .orElseThrow();

        assertThat(active.getDossierVersion()).isEqualTo(2);
        assertThat(active.getMatrixSummaryJson())
                .contains("\"revision_reason\"")
                .contains("\"updated_after_round\":2")
                .contains("\"supersedes_version\":1")
                .contains("\"active_version\":2")
                .contains("\"fact_evidence_matrix\"")
                .contains("用户说明现有证据主要用于证明未实际收到包裹")
                .contains("商家说明物流签收记录来自承运商系统");
        assertThat(eventRepository.findAllByCaseIdOrderBySequenceNoAsc("CASE_EVIDENCE_REVISION"))
                .anySatisfy(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo("EVIDENCE_DOSSIER_REVISED");
                            assertThat(event.getEventJson())
                                    .contains("\"previous_version\":1")
                                    .contains("\"active_version\":2")
                                    .contains("\"updated_after_round\":2");
                        });
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.expireDueRoundsSkipsCasesThatAlreadyLeftTheHearingPhase()」。
    // 具体功能：「HearingCollaborationIntegrationTest.expireDueRoundsSkipsCasesThatAlreadyLeftTheHearingPhase()」：复现“核对完整业务行为（场景方法「expireDueRoundsSkipsCasesThatAlreadyLeftTheHearingPhase」）”场景：驱动 「caseRepository.saveAndFlush」、「roundRepository.saveAndFlush」、「roundService.expireDueRounds」、「roundRepository.findById」，再用 「assertThat」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_POST_HEARING_DUE」、「ORDER-CASE_POST_HEARING_DUE」、「LOG-CASE_POST_HEARING_DUE」、「user-local」。
    // 上游调用：「HearingCollaborationIntegrationTest.expireDueRoundsSkipsCasesThatAlreadyLeftTheHearingPhase()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingCollaborationIntegrationTest.expireDueRoundsSkipsCasesThatAlreadyLeftTheHearingPhase()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.expireDueRoundsSkipsCasesThatAlreadyLeftTheHearingPhase()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_POST_HEARING_DUE」、「ORDER-CASE_POST_HEARING_DUE」、「LOG-CASE_POST_HEARING_DUE」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void expireDueRoundsSkipsCasesThatAlreadyLeftTheHearingPhase() {
        caseRepository.saveAndFlush(
                FulfillmentCaseEntity.imported(
                        "CASE_POST_HEARING_DUE",
                        "ORDER-CASE_POST_HEARING_DUE",
                        null,
                        "LOG-CASE_POST_HEARING_DUE",
                        "user-local",
                        "merchant-local",
                        "idem-CASE_POST_HEARING_DUE",
                        "SIGNED_NOT_RECEIVED",
                        "履约争议",
                        "庭审已完成，等待平台审核",
                        RiskLevel.HIGH,
                        CaseStatus.WAITING_HUMAN_REVIEW,
                        "HEARING",
                        OffsetDateTime.parse("2026-07-03T04:00:00Z"),
                        "OMS",
                        "EXT-CASE_POST_HEARING_DUE",
                        "external-adapter"));
        roundRepository.saveAndFlush(
                HearingRoundEntity.open(
                        "HEARING_ROUND_POST_HEARING_DUE",
                        "CASE_POST_HEARING_DUE",
                        null,
                        3,
                        2,
                        Instant.parse("2026-07-03T00:59:00Z"),
                        Instant.parse("2026-07-03T00:55:00Z"),
                        "system"));

        int expired = roundService.expireDueRounds();

        assertThat(expired).isZero();
        assertThat(
                        roundRepository
                                .findById("HEARING_ROUND_POST_HEARING_DUE")
                                .orElseThrow()
                                .getRoundStatus())
                .isEqualTo(HearingRoundStatus.OPEN);
        verifyNoInteractions(hearingWorkflowCoordinator);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.seedHearing(String)」。
    // 具体功能：「HearingCollaborationIntegrationTest.seedHearing(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「seedHearing」）”组装或读取「caseRepository.saveAndFlush」、「roomRepository.saveAndFlush」、「FulfillmentCaseEntity.imported」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「HearingCollaborationIntegrationTest.seedHearing(String)」由本测试类中的 「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」、「HearingCollaborationIntegrationTest.theThirdCompletedRoundForcesConvergenceWithoutExtendingTheWindow」、「HearingCollaborationIntegrationTest.factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound」、「HearingCollaborationIntegrationTest.openingHearingCreatesInitialRoundWithoutAskingJudgeBeforeBootstrap」 调用。
    // 下游影响：「HearingCollaborationIntegrationTest.seedHearing(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingCollaborationIntegrationTest.seedHearing(String)」守住「共享小法庭」的可执行规格，尤其防止 「ORDER-」、「LOG-」、「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seedHearing(String caseId) {
        caseRepository.saveAndFlush(
                FulfillmentCaseEntity.imported(
                        caseId,
                        "ORDER-" + caseId,
                        null,
                        "LOG-" + caseId,
                        "user-local",
                        "merchant-local",
                        "idem-" + caseId,
                        "SIGNED_NOT_RECEIVED",
                        "履约争议",
                        "双方进入小法庭",
                        RiskLevel.HIGH,
                        CaseStatus.HEARING_OPEN,
                        "HEARING",
                        OffsetDateTime.parse("2026-07-03T04:00:00Z"),
                        "OMS",
                        "EXT-" + caseId,
                        "external-adapter"));
        roomRepository.saveAndFlush(
                CaseRoomEntity.open(
                        "ROOM_HEARING_" + caseId,
                        caseId,
                        RoomType.HEARING,
                        OffsetDateTime.parse("2026-07-03T01:00:00Z"),
                        "system"));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.attachHearingWorkflow(String)」。
    // 具体功能：「HearingCollaborationIntegrationTest.attachHearingWorkflow(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「attachHearingWorkflow」）”组装或读取「caseRepository.findById」、「caseRepository.saveAndFlush」、「dispute.attachHearingWorkflow」，供本测试类的场景方法复用。
    // 上游调用：「HearingCollaborationIntegrationTest.attachHearingWorkflow(String)」由本测试类中的 「HearingCollaborationIntegrationTest.completeHearingDoesNotGenerateADraftWhileTemporalIsDrafting」、「HearingCollaborationIntegrationTest.completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce」、「HearingCollaborationIntegrationTest.completeHearingSynchronouslyCreatesReviewGateForImmediateReviewConfirmation」、「HearingCollaborationIntegrationTest.completeHearingRecoversDraftReadyCaseWithoutWaitingForScheduler」 调用。
    // 下游影响：「HearingCollaborationIntegrationTest.attachHearingWorkflow(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingCollaborationIntegrationTest.attachHearingWorkflow(String)」守住「共享小法庭」的可执行规格，尤其防止 「hearing-window-」、「hearing-bootstrap」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void attachHearingWorkflow(String caseId) {
        FulfillmentCaseEntity dispute = caseRepository.findById(caseId).orElseThrow();
        dispute.attachHearingWorkflow("hearing-window-" + caseId, "hearing-bootstrap");
        caseRepository.saveAndFlush(dispute);
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.seedTemporalFinalDraft(String)」。
    // 具体功能：「HearingCollaborationIntegrationTest.seedTemporalFinalDraft(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「seedTemporalFinalDraft」）”组装或读取「IllegalStateException」、「BigDecimal」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「HearingCollaborationIntegrationTest.seedTemporalFinalDraft(String)」由本测试类中的 「HearingCollaborationIntegrationTest.completeHearingOrchestratesAnExistingTemporalDraftOnlyOnce」、「HearingCollaborationIntegrationTest.completeHearingSynchronouslyCreatesReviewGateForImmediateReviewConfirmation」、「HearingCollaborationIntegrationTest.seededTemporalFinalDraftCanBeAdoptedAfterFormalJuryA2AReport」、「HearingCollaborationIntegrationTest.finalRoundRequiresAFinalDraftVersionInsteadOfReusingEarlierAnalysisDrafts」 调用。
    // 下游影响：「HearingCollaborationIntegrationTest.seedTemporalFinalDraft(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingCollaborationIntegrationTest.seedTemporalFinalDraft(String)」守住「共享小法庭」的可执行规格，尤其防止 「DRAFT_TEMPORAL_C6_」、「[\"三轮庭审已经完整封存\"]」、「[\"证据矩阵已由证据书记官复核\"]」、「[\"平台规则将在人工审核阶段最终确认\"]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private String seedTemporalFinalDraft(String caseId) {
        HearingStateEntity hearingState =
                hearingStateRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "hearing state must be seeded before final draft"));
        String draftId = "DRAFT_TEMPORAL_C6_" + caseId;
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        draftId,
                        caseId,
                        hearingState.getId(),
                        4,
                        "[\"三轮庭审已经完整封存\"]",
                        "[\"证据矩阵已由证据书记官复核\"]",
                        "[\"平台规则将在人工审核阶段最终确认\"]",
                        "[\"审核员复核事实采信与执行可行性\"]",
                        "REVIEWABLE_FINAL_DRAFT",
                        new BigDecimal("0.7500"),
                        "Temporal C6 已生成裁决草案，等待平台审核员确认。",
                        "python-agent-service/presiding-judge",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));
        return draftId;
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.completeCourtOrchestration(String,int,boolean)」。
    // 具体功能：「HearingCollaborationIntegrationTest.completeCourtOrchestration(String,int,boolean)」：作为测试辅助方法为“核对完整业务行为（场景方法「completeCourtOrchestration」）”组装或读取「ArgumentCaptor.forClass」、「completion.capture」、「completion.getValue」、「verify(hearingCourtOrchestrator).afterRoundClosedAfterCommit」，供本测试类的场景方法复用。
    // 上游调用：「HearingCollaborationIntegrationTest.completeCourtOrchestration(String,int,boolean)」由本测试类中的 「HearingCollaborationIntegrationTest.factsSufficientHintDoesNotCloseTheHearingBeforeTheThirdStatementRound」、「HearingCollaborationIntegrationTest.partyRoundSubmissionsWaitForBothSidesBeforeTriggeringJudge」、「HearingCollaborationIntegrationTest.bothPartySubmissionsOpenTheNextRoundUntilTheFinalRound」、「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce」 调用。
    // 下游影响：「HearingCollaborationIntegrationTest.completeCourtOrchestration(String,int,boolean)」的下游是被测服务、仓储或外部客户端替身；「verify、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingCollaborationIntegrationTest.completeCourtOrchestration(String,int,boolean)」守住「共享小法庭」的可执行规格，尤其防止 「TRACE_HEARING_ROUND_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void completeCourtOrchestration(String caseId, int roundNo, boolean finalRound) {
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(hearingCourtOrchestrator)
                .afterRoundClosedAfterCommit(
                        org.mockito.ArgumentMatchers.eq(caseId),
                        org.mockito.ArgumentMatchers.eq(roundNo),
                        org.mockito.ArgumentMatchers.eq(finalRound),
                        org.mockito.ArgumentMatchers.eq("TRACE_HEARING_ROUND_" + roundNo),
                        completion.capture());
        verifyNoInteractions(hearingWorkflowCoordinator);
        completion.getValue().run();
    }

    // 所属模块：【共享小法庭 / 自动化测试层】类型「FixedClockConfiguration」。
    // 类型职责：在 Spring 启动期装配Fixed时钟所需 Bean 和基础设施参数；本类型显式提供 「mutableClock」、「objectMapper」、「transactionTemplate」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.FixedClockConfiguration.mutableClock()」。
        // 具体功能：「HearingCollaborationIntegrationTest.FixedClockConfiguration.mutableClock()」：作为测试辅助方法为“核对完整业务行为（场景方法「mutableClock」）”组装或读取「MutableClock」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「HearingCollaborationIntegrationTest.FixedClockConfiguration.mutableClock()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「HearingCollaborationIntegrationTest.FixedClockConfiguration.mutableClock()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「HearingCollaborationIntegrationTest.FixedClockConfiguration.mutableClock()」守住「共享小法庭」的可执行规格，尤其防止 「2026-07-03T01:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(
                    Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC);
        }

        // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.FixedClockConfiguration.objectMapper()」。
        // 具体功能：「HearingCollaborationIntegrationTest.FixedClockConfiguration.objectMapper()」：作为测试辅助方法为“核对完整业务行为（场景方法「objectMapper」）”组装或读取「ObjectMapper」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「HearingCollaborationIntegrationTest.FixedClockConfiguration.objectMapper()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「HearingCollaborationIntegrationTest.FixedClockConfiguration.objectMapper()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「HearingCollaborationIntegrationTest.FixedClockConfiguration.objectMapper()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.FixedClockConfiguration.transactionTemplate(PlatformTransactionManager)」。
        // 具体功能：「HearingCollaborationIntegrationTest.FixedClockConfiguration.transactionTemplate(PlatformTransactionManager)」：作为测试辅助方法为“核对完整业务行为（场景方法「transactionTemplate」）”组装或读取「TransactionTemplate」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「HearingCollaborationIntegrationTest.FixedClockConfiguration.transactionTemplate(PlatformTransactionManager)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「HearingCollaborationIntegrationTest.FixedClockConfiguration.transactionTemplate(PlatformTransactionManager)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「HearingCollaborationIntegrationTest.FixedClockConfiguration.transactionTemplate(PlatformTransactionManager)」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }

    // 所属模块：【共享小法庭 / 自动化测试层】类型「MutableClock」。
    // 类型职责：承载Mutable时钟在当前业务模块中的规则与协作边界；本类型显式提供 「MutableClock」、「set」、「getZone」、「withZone」、「instant」。
    // 协作关系：主要由 「FixedClockConfiguration.mutableClock」、「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce」、「HearingCollaborationIntegrationTest.setUp」 使用。
    // 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.MutableClock.MutableClock(Instant,ZoneId)」。
        // 具体功能：「HearingCollaborationIntegrationTest.MutableClock.MutableClock(Instant,ZoneId)」：作为测试辅助方法为“核对完整业务行为（场景方法「MutableClock」）”组装或读取「AtomicReference」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「HearingCollaborationIntegrationTest.MutableClock.MutableClock(Instant,ZoneId)」由本测试类中的 「FixedClockConfiguration.mutableClock」、「MutableClock.withZone」 调用。
        // 下游影响：「HearingCollaborationIntegrationTest.MutableClock.MutableClock(Instant,ZoneId)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「HearingCollaborationIntegrationTest.MutableClock.MutableClock(Instant,ZoneId)」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
        MutableClock(Instant initial, ZoneId zone) {
            this.instant = new AtomicReference<>(initial);
            this.zone = zone;
        }

        // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.MutableClock.set(Instant)」。
        // 具体功能：「HearingCollaborationIntegrationTest.MutableClock.set(Instant)」：作为测试辅助方法为“核对完整业务行为（场景方法「set」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
        // 上游调用：「HearingCollaborationIntegrationTest.MutableClock.set(Instant)」由本测试类中的 「HearingCollaborationIntegrationTest.setUp」、「HearingCollaborationIntegrationTest.dueRoundAutoSubmitsMissingPartyAndTriggersJudgeOnce」 调用。
        // 下游影响：「HearingCollaborationIntegrationTest.MutableClock.set(Instant)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「HearingCollaborationIntegrationTest.MutableClock.set(Instant)」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        void set(Instant next) {
            instant.set(next);
        }

        // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.MutableClock.getZone()」。
        // 具体功能：「HearingCollaborationIntegrationTest.MutableClock.getZone()」：作为「MutableClock」测试替身实现「getZone」：返回预设值 「zone」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「HearingCollaborationIntegrationTest.MutableClock.getZone()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「HearingCollaborationIntegrationTest.MutableClock.getZone()」下游仅修改测试内存状态或返回桩值：返回预设值 「zone」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「HearingCollaborationIntegrationTest.MutableClock.getZone()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public ZoneId getZone() {
            return zone;
        }

        // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.MutableClock.withZone(ZoneId)」。
        // 具体功能：「HearingCollaborationIntegrationTest.MutableClock.withZone(ZoneId)」：作为「MutableClock」测试替身实现「withZone」：返回预设值 「newMutableClock(instant.get(),zone)」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「HearingCollaborationIntegrationTest.MutableClock.withZone(ZoneId)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「HearingCollaborationIntegrationTest.MutableClock.withZone(ZoneId)」下游仅修改测试内存状态或返回桩值：返回预设值 「newMutableClock(instant.get(),zone)」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「HearingCollaborationIntegrationTest.MutableClock.withZone(ZoneId)」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant.get(), zone);
        }

        // 所属模块：【共享小法庭 / 自动化测试层】「HearingCollaborationIntegrationTest.MutableClock.instant()」。
        // 具体功能：「HearingCollaborationIntegrationTest.MutableClock.instant()」：作为「MutableClock」测试替身实现「instant」：返回预设值 「instant.get()」，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「HearingCollaborationIntegrationTest.MutableClock.instant()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「HearingCollaborationIntegrationTest.MutableClock.instant()」下游仅修改测试内存状态或返回桩值：返回预设值 「instant.get()」；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「HearingCollaborationIntegrationTest.MutableClock.instant()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
