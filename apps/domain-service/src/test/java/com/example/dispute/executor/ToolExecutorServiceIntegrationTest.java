/*
 * 所属模块：确定性工具执行。
 * 文件职责：验证工具执行器Integration，覆盖 「rejectsEveryUnapprovedHighImpactAction」、「executesApprovedActionsAndNotificationsExactlyOnce」、「persistsFailedExecutionAndIncrementsAttemptOnRetry」、「rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything」、「platformReviewerCannotBypassWorkflowAndExecuteDirectly」、「rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.executor.application.ActionExecutionLock;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.ActionRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.tool.application.SimulatedExecutionTool;
import com.example.dispute.tool.application.ToolRegistry;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.review.domain.ReviewPacketVersions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【确定性工具执行 / 自动化测试层】类型「ToolExecutorServiceIntegrationTest」。
// 类型职责：集中验证工具执行器Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「properties」、「configureExecutionLock」、「rejectsEveryUnapprovedHighImpactAction」、「executesApprovedActionsAndNotificationsExactlyOnce」、「persistsFailedExecutionAndIncrementsAttemptOnRetry」、「rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    ToolExecutorService.class,
    ToolRegistry.class,
    SimulatedExecutionTool.class,
    ToolExecutorServiceIntegrationTest.JacksonConfig.class
})
@Testcontainers
class ToolExecutorServiceIntegrationTest {

    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("temporal-worker", ActorRole.SYSTEM);

    @Container
    static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_executor")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.properties(DynamicPropertyRegistry)」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.properties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「properties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「ToolExecutorServiceIntegrationTest.properties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「ToolExecutorServiceIntegrationTest.properties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ToolExecutorServiceIntegrationTest.properties(DynamicPropertyRegistry)」守住「确定性工具执行」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_executor");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】类型「JacksonConfig」。
    // 类型职责：承载JacksonConfig在当前业务模块中的规则与协作边界；本类型显式提供 「objectMapper」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration
    static class JacksonConfig {
        // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.JacksonConfig.objectMapper()」。
        // 具体功能：「ToolExecutorServiceIntegrationTest.JacksonConfig.objectMapper()」：作为测试辅助方法为“核对完整业务行为（场景方法「objectMapper」）”组装或读取「ObjectMapper」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「ToolExecutorServiceIntegrationTest.JacksonConfig.objectMapper()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「ToolExecutorServiceIntegrationTest.JacksonConfig.objectMapper()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「ToolExecutorServiceIntegrationTest.JacksonConfig.objectMapper()」守住「确定性工具执行」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .findAndRegisterModules()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
    }

    @Autowired private ToolExecutorService service;
    @Autowired private FulfillmentCaseRepository cases;
    @Autowired private RemedyPlanRepository plans;
    @Autowired private ApprovalRecordRepository approvals;
    @Autowired private ActionRecordRepository actions;
    @Autowired private ReviewPacketRepository packets;
    @Autowired private ReviewTaskRepository reviewTasks;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private AuditRecorder auditRecorder;
    @MockitoBean private ActionExecutionLock executionLock;

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.configureExecutionLock()」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.configureExecutionLock()」：在每个测试场景运行前创建「executionLock.acquire」、「when」、「anyString」、「when(executionLock.acquire(anyString())).thenReturn」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「ToolExecutorServiceIntegrationTest.configureExecutionLock()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「ToolExecutorServiceIntegrationTest.configureExecutionLock()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ToolExecutorServiceIntegrationTest.configureExecutionLock()」守住「确定性工具执行」的可执行规格，尤其防止 「test-lock-owner」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void configureExecutionLock() {
        when(executionLock.acquire(anyString())).thenReturn("test-lock-owner");
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction(String)」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction(String)」：复现“拒绝非法输入或越权操作（场景方法「rejectsEveryUnapprovedHighImpactAction」）”场景：驱动 「service.executeApprovedActions」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「unapproved_」、「CASE_」、「COMMAND_」、「approved」。
    // 上游调用：「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction(String)」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction(String)」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction(String)」守住「确定性工具执行」的可执行规格，尤其防止 「unapproved_」、「CASE_」、「COMMAND_」、「approved」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @ParameterizedTest
    @ValueSource(strings = {"REFUND", "RESHIP", "CLOSE_AFTER_SALE"})
    void rejectsEveryUnapprovedHighImpactAction(String actionType) {
        String suffix = "unapproved_" + actionType.toLowerCase();
        seed(suffix, false, false, false, actionType);

        assertThatThrownBy(
                        () ->
                                service.executeApprovedActions(
                                        "CASE_" + suffix,
                                        "COMMAND_" + suffix,
                                        SYSTEM))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("approved");
        assertThat(
                        actions.findAllByCaseIdOrderByCreatedAtAsc(
                                "CASE_" + suffix))
                .isEmpty();
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.executesApprovedActionsAndNotificationsExactlyOnce()」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.executesApprovedActionsAndNotificationsExactlyOnce()」：复现“核对完整业务行为（场景方法「executesApprovedActionsAndNotificationsExactlyOnce」）”场景：驱动 「service.executeApprovedActions」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「success」、「CASE_success」、「COMMAND_success_1」、「COMMAND_success_2」。
    // 上游调用：「ToolExecutorServiceIntegrationTest.executesApprovedActionsAndNotificationsExactlyOnce()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ToolExecutorServiceIntegrationTest.executesApprovedActionsAndNotificationsExactlyOnce()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ToolExecutorServiceIntegrationTest.executesApprovedActionsAndNotificationsExactlyOnce()」守住「确定性工具执行」的可执行规格，尤其防止 「success」、「CASE_success」、「COMMAND_success_1」、「COMMAND_success_2」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    @Test
    void executesApprovedActionsAndNotificationsExactlyOnce() {
        seed("success", true, false);

        var first =
                service.executeApprovedActions(
                        "CASE_success", "COMMAND_success_1", SYSTEM);
        var retry =
                service.executeApprovedActions(
                        "CASE_success", "COMMAND_success_2", SYSTEM);

        assertThat(first.actions()).hasSize(4);
        assertThat(first.actions())
                .allSatisfy(
                        action -> {
                            assertThat(action.reviewPacketId())
                                    .isEqualTo("PACKET_success");
                            assertThat(action.actionSnapshotHash()).isNotBlank();
                            assertThat(action.agentRunRefs().get(0).asText())
                                    .isEqualTo("RUN_success");
                            assertThat(action.externalResultRef()).isNotBlank();
                        });
        assertThat(retry.actions())
                .extracting(action -> action.actionRecordId())
                .containsExactlyElementsOf(
                        first.actions().stream()
                                .map(action -> action.actionRecordId())
                                .toList());
        assertThat(actions.findAllByCaseIdOrderByCreatedAtAsc("CASE_success"))
                .hasSize(4)
                .allSatisfy(
                        action -> {
                            assertThat(action.getExecutionStatus())
                                    .isEqualTo(ExecutionStatus.SUCCEEDED);
                            assertThat(action.getAttemptCount()).isEqualTo(1);
                            assertThat(action.getReviewPacketId())
                                    .isEqualTo("PACKET_success");
                            assertThat(action.getActionSnapshotHash()).isNotBlank();
                            assertThat(action.getAgentRunRefsJson())
                                    .contains("RUN_success");
                            assertThat(action.getExternalResultRef()).isNotBlank();
                        });
        assertThat(cases.findById("CASE_success"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(CaseStatus.EXECUTING));
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.persistsFailedExecutionAndIncrementsAttemptOnRetry()」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.persistsFailedExecutionAndIncrementsAttemptOnRetry()」：复现“持久化业务事实（场景方法「persistsFailedExecutionAndIncrementsAttemptOnRetry」）”场景：驱动 「service.executeApprovedActions」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「failure」、「CASE_failure」、「COMMAND_failure_1」、「COMMAND_failure_2」。
    // 上游调用：「ToolExecutorServiceIntegrationTest.persistsFailedExecutionAndIncrementsAttemptOnRetry()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ToolExecutorServiceIntegrationTest.persistsFailedExecutionAndIncrementsAttemptOnRetry()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ToolExecutorServiceIntegrationTest.persistsFailedExecutionAndIncrementsAttemptOnRetry()」守住「确定性工具执行」的可执行规格，尤其防止 「failure」、「CASE_failure」、「COMMAND_failure_1」、「COMMAND_failure_2」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void persistsFailedExecutionAndIncrementsAttemptOnRetry() {
        seed("failure", true, true);

        assertThatThrownBy(
                        () ->
                                service.executeApprovedActions(
                                        "CASE_failure", "COMMAND_failure_1", SYSTEM))
                .isInstanceOf(ToolExecutionException.class);
        assertThatThrownBy(
                        () ->
                                service.executeApprovedActions(
                                        "CASE_failure", "COMMAND_failure_2", SYSTEM))
                .isInstanceOf(ToolExecutionException.class);

        assertThat(actions.findAllByCaseIdOrderByCreatedAtAsc("CASE_failure"))
                .singleElement()
                .satisfies(
                        action -> {
                            assertThat(action.getExecutionStatus())
                                    .isEqualTo(ExecutionStatus.FAILED);
                            assertThat(action.getAttemptCount()).isEqualTo(2);
                            assertThat(action.getErrorCode())
                                    .isEqualTo("TOOL_EXECUTION_FAILED");
                        });
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything()」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything()」：复现“拒绝非法输入或越权操作（场景方法「rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything」）”场景：驱动 「service.executeApprovedActions」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「injected」、「CASE_injected」、「COMMAND_injected」。
    // 上游调用：「ToolExecutorServiceIntegrationTest.rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ToolExecutorServiceIntegrationTest.rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ToolExecutorServiceIntegrationTest.rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything()」守住「确定性工具执行」的可执行规格，尤其防止 「injected」、「CASE_injected」、「COMMAND_injected」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything() {
        seed("injected", true, false, true);

        assertThatThrownBy(
                        () ->
                                service.executeApprovedActions(
                                        "CASE_injected",
                                        "COMMAND_injected",
                                        SYSTEM))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not contained");
        assertThat(actions.findAllByCaseIdOrderByCreatedAtAsc("CASE_injected"))
                .isEmpty();
        assertThat(cases.findById("CASE_injected"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(
                                                CaseStatus
                                                        .APPROVED_FOR_EXECUTION));
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.platformReviewerCannotBypassWorkflowAndExecuteDirectly()」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.platformReviewerCannotBypassWorkflowAndExecuteDirectly()」：复现“核对完整业务行为（场景方法「platformReviewerCannotBypassWorkflowAndExecuteDirectly」）”场景：驱动 「service.executeApprovedActions」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「reviewer_denied」、「reviewer-reviewer_denied」、「CASE_reviewer_denied」、「COMMAND_reviewer_denied」。
    // 上游调用：「ToolExecutorServiceIntegrationTest.platformReviewerCannotBypassWorkflowAndExecuteDirectly()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ToolExecutorServiceIntegrationTest.platformReviewerCannotBypassWorkflowAndExecuteDirectly()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ToolExecutorServiceIntegrationTest.platformReviewerCannotBypassWorkflowAndExecuteDirectly()」守住「确定性工具执行」的可执行规格，尤其防止 「reviewer_denied」、「reviewer-reviewer_denied」、「CASE_reviewer_denied」、「COMMAND_reviewer_denied」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void platformReviewerCannotBypassWorkflowAndExecuteDirectly() {
        seed("reviewer_denied", true, false);
        AuthenticatedActor reviewer =
                new AuthenticatedActor(
                        "reviewer-reviewer_denied",
                        ActorRole.PLATFORM_REVIEWER);

        assertThatThrownBy(
                        () ->
                                service.executeApprovedActions(
                                        "CASE_reviewer_denied",
                                        "COMMAND_reviewer_denied",
                                        reviewer))
                .isInstanceOf(ForbiddenException.class);
        assertThat(
                        actions.findAllByCaseIdOrderByCreatedAtAsc(
                                "CASE_reviewer_denied"))
                .isEmpty();
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals()」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals()」：复现“拒绝非法输入或越权操作（场景方法「rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals」）”场景：驱动 「ReflectionTestUtils.setField」、「ReviewPacketEntity.createFrozen」、「approvals.findById」、「approvals.saveAndFlush」，再用 测试框架断言 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「expired_gate」、「APPROVAL_expired_gate」、「approvalExpiresAt」、「expired」。
    // 上游调用：「ToolExecutorServiceIntegrationTest.rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ToolExecutorServiceIntegrationTest.rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ToolExecutorServiceIntegrationTest.rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals()」守住「确定性工具执行」的可执行规格，尤其防止 「expired_gate」、「APPROVAL_expired_gate」、「approvalExpiresAt」、「expired」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals() {
        seed("expired_gate", true, false);
        var expired =
                approvals
                        .findById("APPROVAL_expired_gate")
                        .orElseThrow();
        ReflectionTestUtils.setField(
                expired,
                "approvalExpiresAt",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        approvals.saveAndFlush(expired);
        assertDenied("expired_gate", "expired");

        seed("role_gate", true, false);
        var wrongRole =
                approvals.findById("APPROVAL_role_gate").orElseThrow();
        ReflectionTestUtils.setField(wrongRole, "reviewerRole", "ADMIN");
        approvals.saveAndFlush(wrongRole);
        assertDenied("role_gate", "required reviewer role");

        seed("hash_gate", true, false);
        var wrongHash =
                approvals.findById("APPROVAL_hash_gate").orElseThrow();
        ReflectionTestUtils.setField(
                wrongHash, "actionSnapshotHash", "WRONG_HASH");
        approvals.saveAndFlush(wrongHash);
        assertDenied("hash_gate", "hash");

        seed("stale_gate", true, false);
        var staleApproval =
                approvals.findById("APPROVAL_stale_gate").orElseThrow();
        var oldPacket =
                packets.findById("PACKET_stale_gate").orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        packets.saveAndFlush(
                ReviewPacketEntity.createFrozen(
                        "PACKET_stale_gate_v2",
                        "CASE_stale_gate",
                        "REMEDY_stale_gate",
                        2,
                        new ReviewPacketVersions(
                                2,
                                1,
                                1,
                                1,
                                0,
                                1,
                                "ruleset-test",
                                "prompt-test",
                                "skill-test",
                                "profile-test"),
                        oldPacket.getActionHash(),
                        now,
                        now.plusDays(1),
                        "{}",
                        "[]",
                        "[]",
                        "[]",
                        "{}",
                        staleApproval.getApprovedPlanJson(),
                        "[]",
                        "temporal-worker"));
        assertDenied("stale_gate", "stale");
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.assertDenied(String,String)」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.assertDenied(String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「assertDenied」）”组装或读取「service.executeApprovedActions」、「actions.findAllByCaseIdOrderByCreatedAtAsc」、「hasMessageContaining」、「isInstanceOf」，供本测试类的场景方法复用。
    // 上游调用：「ToolExecutorServiceIntegrationTest.assertDenied(String,String)」由本测试类中的 「ToolExecutorServiceIntegrationTest.rejectsExpiredUnauthorizedStaleAndHashMismatchedApprovals」 调用。
    // 下游影响：「ToolExecutorServiceIntegrationTest.assertDenied(String,String)」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ToolExecutorServiceIntegrationTest.assertDenied(String,String)」守住「确定性工具执行」的可执行规格，尤其防止 「CASE_」、「COMMAND_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void assertDenied(String suffix, String message) {
        assertThatThrownBy(
                        () ->
                                service.executeApprovedActions(
                                        "CASE_" + suffix,
                                        "COMMAND_" + suffix,
                                        SYSTEM))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining(message);
        assertThat(
                        actions.findAllByCaseIdOrderByCreatedAtAsc(
                                "CASE_" + suffix))
                .isEmpty();
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean)」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean)」：作为测试辅助方法为“核对完整业务行为（场景方法「seed」）”组装或读取「seed」，供本测试类的场景方法复用。
    // 上游调用：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean)」由本测试类中的 「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction」、「ToolExecutorServiceIntegrationTest.executesApprovedActionsAndNotificationsExactlyOnce」、「ToolExecutorServiceIntegrationTest.persistsFailedExecutionAndIncrementsAttemptOnRetry」、「ToolExecutorServiceIntegrationTest.rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything」 调用。
    // 下游影响：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean)」守住「确定性工具执行」的可执行规格，尤其防止 「REFUND」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seed(String suffix, boolean approved, boolean simulateFailure) {
        seed(suffix, approved, simulateFailure, false, "REFUND");
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean)」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean)」：作为测试辅助方法为“核对完整业务行为（场景方法「seed」）”组装或读取「seed」，供本测试类的场景方法复用。
    // 上游调用：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean)」由本测试类中的 「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction」、「ToolExecutorServiceIntegrationTest.executesApprovedActionsAndNotificationsExactlyOnce」、「ToolExecutorServiceIntegrationTest.persistsFailedExecutionAndIncrementsAttemptOnRetry」、「ToolExecutorServiceIntegrationTest.rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything」 调用。
    // 下游影响：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean)」守住「确定性工具执行」的可执行规格，尤其防止 「REFUND」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seed(
            String suffix,
            boolean approved,
            boolean simulateFailure,
            boolean injectUnapprovedAction) {
        seed(
                suffix,
                approved,
                simulateFailure,
                injectUnapprovedAction,
                "REFUND");
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean,String)」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「seed」）”组装或读取「ReviewPacketVersions」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean,String)」由本测试类中的 「ToolExecutorServiceIntegrationTest.rejectsEveryUnapprovedHighImpactAction」、「ToolExecutorServiceIntegrationTest.executesApprovedActionsAndNotificationsExactlyOnce」、「ToolExecutorServiceIntegrationTest.persistsFailedExecutionAndIncrementsAttemptOnRetry」、「ToolExecutorServiceIntegrationTest.rejectsAnActionInjectedIntoTheApprovalSnapshotWithoutExecutingAnything」 调用。
    // 下游影响：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ToolExecutorServiceIntegrationTest.seed(String,boolean,boolean,boolean,String)」守住「确定性工具执行」的可执行规格，尤其防止 「CASE_」、「REMEDY_」、「ORDER_」、「user-」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seed(
            String suffix,
            boolean approved,
            boolean simulateFailure,
            boolean injectUnapprovedAction,
            String actionType) {
        String caseId = "CASE_" + suffix;
        String planId = "REMEDY_" + suffix;
        String actionJson =
                """
                [{"action_type":"%s",
                  "idempotency_key":"REMEDY:%s:1:0:%s",
                  "preconditions":["PLATFORM_REVIEW_APPROVED"],
                  "risk_level":"HIGH",
                  "requires_approval":true,
                  "parameters":{"simulate_failure":%s}}]
                """
                        .formatted(
                                actionType,
                                caseId,
                                actionType,
                                simulateFailure);
        String notifications =
                """
                ["NOTIFY_USER_AFTER_EXECUTION",
                 "NOTIFY_MERCHANT_AFTER_EXECUTION",
                 "AUDIT_EXECUTION_RESULT"]
                """;
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + suffix,
                        null,
                        "user-" + suffix,
                        "merchant-" + suffix,
                        "CREATE_" + suffix,
                        "REFUND_REQUEST",
                        "Executor " + suffix,
                        "Executor integration test",
                        RiskLevel.HIGH,
                        "user-" + suffix);
        disputeCase.completeIntake(
                "REFUND_DISPUTE",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                "{}",
                "user-" + suffix);
        disputeCase.markDossierBuilt("user-" + suffix);
        disputeCase.applyRoute(RouteType.SIMPLE_HEARING, "user-" + suffix);
        disputeCase.markRemedyPlanned("temporal-worker");
        if (approved) {
            disputeCase.waitForHumanReview("temporal-worker");
            disputeCase.applyReviewOutcome(
                    ApprovalDecisionType.APPROVE, "reviewer-" + suffix);
        }
        cases.saveAndFlush(disputeCase);
        plans.saveAndFlush(
                RemedyPlanEntity.pendingApproval(
                        planId,
                        caseId,
                        null,
                        1,
                        RouteType.SIMPLE_HEARING,
                        RiskLevel.HIGH,
                        actionJson,
                        "[\"PLATFORM_REVIEW_APPROVED\"]",
                        notifications,
                        "temporal-worker"));
        if (approved) {
            String approvedActions =
                    injectUnapprovedAction
                            ? actionJson.replace(
                                    "REMEDY:%s:1:0:%s"
                                            .formatted(caseId, actionType),
                                    "INJECTED:%s:RESHIP".formatted(caseId))
                            : actionJson;
            String approvedPlan =
                    """
                    {"id":"%s","version":1,"actions":%s,"preconditions":
                    ["PLATFORM_REVIEW_APPROVED"],"notifications":%s}
                            """
                            .formatted(planId, approvedActions, notifications);
            String packetId = "PACKET_" + suffix;
            String taskId = "REVIEW_" + suffix;
            var approvedPlanNode = read(approvedPlan);
            String actionHash =
                    ActionSnapshotHasher.hash(objectMapper, approvedPlanNode);
            OffsetDateTime frozenAt = OffsetDateTime.now(ZoneOffset.UTC);
            packets.saveAndFlush(
                    ReviewPacketEntity.createFrozen(
                            packetId,
                            caseId,
                            planId,
                            1,
                            new ReviewPacketVersions(
                                    1,
                                    1,
                                    1,
                                    1,
                                    0,
                                    1,
                                    "ruleset-test",
                                    "prompt-test",
                                    "skill-test",
                                    "profile-test"),
                            actionHash,
                            frozenAt,
                            frozenAt.plusDays(1),
                            "[\"RUN_" + suffix + "\"]",
                            "{}",
                            "[]",
                            "[]",
                            "[]",
                            "{}",
                            approvedPlan,
                            "[]",
                            "temporal-worker"));
            ReviewTaskEntity reviewTask =
                    ReviewTaskEntity.pending(
                            taskId,
                            caseId,
                            planId,
                            packetId,
                            "URGENT",
                            ActorRole.PLATFORM_REVIEWER.name(),
                            OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                            "temporal-worker");
            reviewTask.decide(
                    ApprovalDecisionType.APPROVE,
                    "reviewer-" + suffix,
                    "{\"decision\":\"APPROVE\"}");
            reviewTasks.saveAndFlush(reviewTask);
            approvals.saveAndFlush(
                    ApprovalRecordEntity.recordFrozen(
                            "APPROVAL_" + suffix,
                            caseId,
                            taskId,
                            planId,
                            "reviewer-" + suffix,
                            ActorRole.PLATFORM_REVIEWER.name(),
                            ApprovalDecisionType.APPROVE,
                            approvedPlan,
                            approvedPlan,
                            "approved for execution",
                            "HASH_" + suffix,
                            packetId,
                            1,
                            "approval-policy-v1",
                            actionHash,
                            frozenAt.plusDays(1)));
        }
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「ToolExecutorServiceIntegrationTest.read(String)」。
    // 具体功能：「ToolExecutorServiceIntegrationTest.read(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「read」）”组装或读取「IllegalStateException」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「ToolExecutorServiceIntegrationTest.read(String)」由本测试类中的 「ToolExecutorServiceIntegrationTest.seed」 调用。
    // 下游影响：「ToolExecutorServiceIntegrationTest.read(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ToolExecutorServiceIntegrationTest.read(String)」守住「确定性工具执行」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private com.fasterxml.jackson.databind.JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
