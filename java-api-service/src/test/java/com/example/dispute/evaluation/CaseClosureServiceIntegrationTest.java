/*
 * 所属模块：结案与离线评估。
 * 文件职责：验证案件结案Integration，覆盖 「closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation」、「rejectsClosureUntilEveryApprovedActionSucceeded」、「evaluationCanOnlyBeReadByAdministratorOrSystem」、「agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；关闭已执行案件并调用评估 Agent 生成质量指标和离线报告。
 * 关键边界：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
 */
package com.example.dispute.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.exception.CaseClosureException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.evaluation.application.EvaluationAgentClient;
import com.example.dispute.evaluation.application.EvaluationAgentResult;
import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.ActionRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.EvaluationTraceRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【结案与离线评估 / 自动化测试层】类型「CaseClosureServiceIntegrationTest」。
// 类型职责：集中验证案件结案Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「properties」、「configureAgent」、「closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation」、「rejectsClosureUntilEveryApprovedActionSucceeded」、「evaluationCanOnlyBeReadByAdministratorOrSystem」、「agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    CaseClosureService.class,
    CaseClosureServiceIntegrationTest.JacksonConfig.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseClosureServiceIntegrationTest {

    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("temporal-worker", ActorRole.SYSTEM);

    @Container
    static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_closure")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【结案与离线评估 / 自动化测试层】「CaseClosureServiceIntegrationTest.properties(DynamicPropertyRegistry)」。
    // 具体功能：「CaseClosureServiceIntegrationTest.properties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「properties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「CaseClosureServiceIntegrationTest.properties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「CaseClosureServiceIntegrationTest.properties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseClosureServiceIntegrationTest.properties(DynamicPropertyRegistry)」守住「结案与离线评估」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_closure");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    // 所属模块：【结案与离线评估 / 自动化测试层】类型「JacksonConfig」。
    // 类型职责：承载JacksonConfig在当前业务模块中的规则与协作边界；本类型显式提供 「objectMapper」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration
    static class JacksonConfig {
        // 所属模块：【结案与离线评估 / 自动化测试层】「CaseClosureServiceIntegrationTest.JacksonConfig.objectMapper()」。
        // 具体功能：「CaseClosureServiceIntegrationTest.JacksonConfig.objectMapper()」：作为测试辅助方法为“核对完整业务行为（场景方法「objectMapper」）”组装或读取「ObjectMapper」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「CaseClosureServiceIntegrationTest.JacksonConfig.objectMapper()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「CaseClosureServiceIntegrationTest.JacksonConfig.objectMapper()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「CaseClosureServiceIntegrationTest.JacksonConfig.objectMapper()」守住「结案与离线评估」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .findAndRegisterModules()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
    }

    @Autowired CaseClosureService service;
    @Autowired FulfillmentCaseRepository cases;
    @Autowired RemedyPlanRepository plans;
    @Autowired ReviewPacketRepository packets;
    @Autowired ReviewTaskRepository reviewTasks;
    @Autowired ApprovalRecordRepository approvals;
    @Autowired ActionRecordRepository actions;
    @Autowired EvaluationTraceRepository evaluations;
    @MockitoBean EvaluationAgentClient evaluationAgent;
    @MockitoBean AuditRecorder auditRecorder;
    @MockitoBean CaseLifecycleNotificationService lifecycleNotifications;

    // 所属模块：【结案与离线评估 / 自动化测试层】「CaseClosureServiceIntegrationTest.configureAgent()」。
    // 具体功能：「CaseClosureServiceIntegrationTest.configureAgent()」：在每个测试场景运行前创建「actions.deleteAllInBatch」、「evaluations.deleteAllInBatch」、「approvals.deleteAllInBatch」、「reviewTasks.deleteAllInBatch」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「CaseClosureServiceIntegrationTest.configureAgent()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「CaseClosureServiceIntegrationTest.configureAgent()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseClosureServiceIntegrationTest.configureAgent()」守住「结案与离线评估」的可执行规格，尤其防止 「case_id」、「evaluation-model」、「evaluation-v1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void configureAgent() throws Exception {
        actions.deleteAllInBatch();
        evaluations.deleteAllInBatch();
        approvals.deleteAllInBatch();
        reviewTasks.deleteAllInBatch();
        packets.deleteAllInBatch();
        plans.deleteAllInBatch();
        cases.deleteAllInBatch();
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .setPropertyNamingStrategy(
                                PropertyNamingStrategies.SNAKE_CASE);
        when(evaluationAgent.analyze(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction
                                                    .support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var report =
                                    (com.fasterxml.jackson.databind.node.ObjectNode)
                                            mapper.readTree(
                                        """
                                        {
                                          "case_id":"CASE_pending",
                                          "evaluation_status":"COMPLETED",
                                          "metric_scores":{
                                            "draft_approval_rate":1.0,
                                            "reviewer_modification_rate":0.0,
                                            "evidence_quality_score":0.8,
                                            "policy_coverage_score":0.7,
                                            "execution_quality_score":1.0,
                                            "process_quality_score":0.9,
                                            "overall_quality_score":0.88
                                          },
                                          "findings":[],
                                          "rule_gap_suggestions":["Clarify exceptions."],
                                          "improvement_suggestions":["Keep evidence structured."],
                                          "automatic_changes_applied":false,
                                          "online_case_mutated":false,
                                          "evaluator_model":"evaluation-model",
                                          "prompt_version":"evaluation-v1",
                                          "latency_ms":12,
                                          "token_usage":33
                                        }
                                        """);
                            report.put(
                                    "case_id",
                                    invocation
                                            .<com.fasterxml.jackson.databind.JsonNode>
                                                    getArgument(0)
                                            .path("case_id")
                                            .asText());
                            return new EvaluationAgentResult(
                                    report,
                                    "evaluation-model",
                                    "evaluation-v1",
                                    12,
                                    33);
                        });
    }

    // 所属模块：【结案与离线评估 / 自动化测试层】「CaseClosureServiceIntegrationTest.closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation()」。
    // 具体功能：「CaseClosureServiceIntegrationTest.closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation()」：复现“核对完整业务行为（场景方法「closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation」）”场景：驱动 「service.close」、「service.metrics」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「success」、「CASE_success」、「CLOSE_success」、「TRACE_success」。
    // 上游调用：「CaseClosureServiceIntegrationTest.closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseClosureServiceIntegrationTest.closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseClosureServiceIntegrationTest.closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation()」守住「结案与离线评估」的可执行规格，尤其防止 「success」、「CASE_success」、「CLOSE_success」、「TRACE_success」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation() {
        seed("success", true);

        var first =
                service.close(
                        "CASE_success",
                        "CLOSE_success",
                        SYSTEM,
                        "TRACE_success",
                        "REQ_success");
        var retry =
                service.close(
                        "CASE_success",
                        "CLOSE_success",
                        SYSTEM,
                        "TRACE_success_retry",
                        "REQ_success_retry");

        assertThat(first.caseStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(first.closedAt()).isNotNull();
        assertThat(first.evaluationStatus()).isEqualTo("COMPLETED");
        assertThat(retry.evaluationTraceId()).isEqualTo(first.evaluationTraceId());
        assertThat(cases.findById("CASE_success"))
                .hasValueSatisfying(
                        disputeCase -> {
                            assertThat(disputeCase.getCaseStatus())
                                    .isEqualTo(CaseStatus.CLOSED);
                            assertThat(disputeCase.getClosedAt()).isNotNull();
                        });
        assertThat(evaluations.findAll()).singleElement()
                .satisfies(
                        trace -> {
                            assertThat(trace.getEvaluationStatus())
                                    .isEqualTo("COMPLETED");
                            assertThat(trace.getMetricScoresJson())
                                    .contains("draft_approval_rate");
                            assertThat(trace.getReportJson())
                                    .containsPattern(
                                            "\"online_case_mutated\"\\s*:\\s*false");
                        });
        assertThat(service.metrics(SYSTEM))
                .satisfies(
                        metrics -> {
                            assertThat(metrics.totalEvaluations()).isEqualTo(1);
                            assertThat(metrics.completedEvaluations())
                                    .isEqualTo(1);
                            assertThat(metrics.draftApprovalRate())
                                    .isEqualTo(1.0);
                            assertThat(metrics.reviewerModificationRate())
                                    .isEqualTo(0.0);
                        });
        verify(evaluationAgent, times(1))
                .analyze(any(), eq("TRACE_success"), eq("REQ_success"));
        verify(auditRecorder)
                .record(
                        any(),
                        eq("CASE_CLOSED"),
                        eq("FULFILLMENT_CASE"),
                        eq("CASE_success"),
                        eq("CASE_success"),
                        any(),
                        any());
        verify(lifecycleNotifications, times(1))
                .executionCompleted(any(FulfillmentCaseEntity.class));
    }

    // 所属模块：【结案与离线评估 / 自动化测试层】「CaseClosureServiceIntegrationTest.rejectsClosureUntilEveryApprovedActionSucceeded()」。
    // 具体功能：「CaseClosureServiceIntegrationTest.rejectsClosureUntilEveryApprovedActionSucceeded()」：复现“拒绝非法输入或越权操作（场景方法「rejectsClosureUntilEveryApprovedActionSucceeded」）”场景：驱动 「service.close」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「failed_action」、「CASE_failed_action」、「CLOSE_failed_action」、「TRACE_failed_action」。
    // 上游调用：「CaseClosureServiceIntegrationTest.rejectsClosureUntilEveryApprovedActionSucceeded()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseClosureServiceIntegrationTest.rejectsClosureUntilEveryApprovedActionSucceeded()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseClosureServiceIntegrationTest.rejectsClosureUntilEveryApprovedActionSucceeded()」守住「结案与离线评估」的可执行规格，尤其防止 「failed_action」、「CASE_failed_action」、「CLOSE_failed_action」、「TRACE_failed_action」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsClosureUntilEveryApprovedActionSucceeded() {
        seed("failed_action", false);

        assertThatThrownBy(
                        () ->
                                service.close(
                                        "CASE_failed_action",
                                        "CLOSE_failed_action",
                                        SYSTEM,
                                        "TRACE_failed_action",
                                        "REQ_failed_action"))
                .isInstanceOf(CaseClosureException.class)
                .hasMessageContaining("succeeded");
        assertThat(cases.findById("CASE_failed_action"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(CaseStatus.EXECUTING));
        assertThat(evaluations.findAll()).isEmpty();
    }

    // 所属模块：【结案与离线评估 / 自动化测试层】「CaseClosureServiceIntegrationTest.evaluationCanOnlyBeReadByAdministratorOrSystem()」。
    // 具体功能：「CaseClosureServiceIntegrationTest.evaluationCanOnlyBeReadByAdministratorOrSystem()」：复现“核对完整业务行为（场景方法「evaluationCanOnlyBeReadByAdministratorOrSystem」）”场景：驱动 「service.close」、「service.evaluation」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「visibility」、「CASE_visibility」、「CLOSE_visibility」、「TRACE_visibility」。
    // 上游调用：「CaseClosureServiceIntegrationTest.evaluationCanOnlyBeReadByAdministratorOrSystem()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseClosureServiceIntegrationTest.evaluationCanOnlyBeReadByAdministratorOrSystem()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseClosureServiceIntegrationTest.evaluationCanOnlyBeReadByAdministratorOrSystem()」守住「结案与离线评估」的可执行规格，尤其防止 「visibility」、「CASE_visibility」、「CLOSE_visibility」、「TRACE_visibility」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void evaluationCanOnlyBeReadByAdministratorOrSystem() {
        seed("visibility", true);
        service.close(
                "CASE_visibility",
                "CLOSE_visibility",
                SYSTEM,
                "TRACE_visibility",
                "REQ_visibility");

        assertThatThrownBy(
                        () ->
                                service.evaluation(
                                        "CASE_visibility",
                                        new AuthenticatedActor(
                                                "reviewer-1",
                                                ActorRole.PLATFORM_REVIEWER)))
                .isInstanceOf(
                        com.example.dispute.common.exception.ForbiddenException
                                .class);
        assertThat(service.evaluation("CASE_visibility", SYSTEM).report())
                .isNotNull();
    }

    // 所属模块：【结案与离线评估 / 自动化测试层】「CaseClosureServiceIntegrationTest.agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace()」。
    // 具体功能：「CaseClosureServiceIntegrationTest.agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace()」：复现“核对完整业务行为（场景方法「agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace」）”场景：驱动 「service.close」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「agent_failure」、「CASE_agent_failure」、「CLOSE_agent_failure」、「TRACE_agent_failure」。
    // 上游调用：「CaseClosureServiceIntegrationTest.agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseClosureServiceIntegrationTest.agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseClosureServiceIntegrationTest.agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace()」守住「结案与离线评估」的可执行规格，尤其防止 「agent_failure」、「CASE_agent_failure」、「CLOSE_agent_failure」、「TRACE_agent_failure」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace() {
        seed("agent_failure", true);
        doThrow(
                        new AgentExecutionException(
                                ErrorCode.AGENT_SERVICE_UNAVAILABLE,
                                "evaluation unavailable",
                                java.util.Map.of()))
                .when(evaluationAgent)
                .analyze(any(), any(), any());

        assertThatThrownBy(
                        () ->
                                service.close(
                                        "CASE_agent_failure",
                                        "CLOSE_agent_failure",
                                        SYSTEM,
                                        "TRACE_agent_failure",
                                        "REQ_agent_failure"))
                .isInstanceOf(AgentExecutionException.class);

        assertThat(cases.findById("CASE_agent_failure"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(CaseStatus.CLOSED));
        assertThat(evaluations.findAll()).singleElement()
                .satisfies(
                        trace -> {
                            assertThat(trace.getEvaluationStatus())
                                    .isEqualTo("FAILED");
                            assertThat(trace.getReportJson())
                                    .contains("AgentExecutionException");
                        });
    }

    // 所属模块：【结案与离线评估 / 自动化测试层】「CaseClosureServiceIntegrationTest.seed(String,boolean)」。
    // 具体功能：「CaseClosureServiceIntegrationTest.seed(String,boolean)」：作为测试辅助方法为“核对完整业务行为（场景方法「seed」）”组装或读取「FulfillmentCaseEntity.create」、「RemedyPlanEntity.pendingApproval」、「ReviewPacketEntity.create」、「ReviewTaskEntity.pending」，供本测试类的场景方法复用。
    // 上游调用：「CaseClosureServiceIntegrationTest.seed(String,boolean)」由本测试类中的 「CaseClosureServiceIntegrationTest.closesExecutedCaseAndCreatesExactlyOneCompletedEvaluation」、「CaseClosureServiceIntegrationTest.rejectsClosureUntilEveryApprovedActionSucceeded」、「CaseClosureServiceIntegrationTest.evaluationCanOnlyBeReadByAdministratorOrSystem」、「CaseClosureServiceIntegrationTest.agentFailureKeepsCaseClosedAndPersistsFailedEvaluationTrace」 调用。
    // 下游影响：「CaseClosureServiceIntegrationTest.seed(String,boolean)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「CaseClosureServiceIntegrationTest.seed(String,boolean)」守住「结案与离线评估」的可执行规格，尤其防止 「CASE_」、「REMEDY_」、「PACKET_」、「REVIEW_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seed(String suffix, boolean succeeded) {
        String caseId = "CASE_" + suffix;
        String planId = "REMEDY_" + suffix;
        String packetId = "PACKET_" + suffix;
        String taskId = "REVIEW_" + suffix;
        String approvalId = "APPROVAL_" + suffix;
        String actionJson =
                """
                [{"action_type":"REFUND",
                  "idempotency_key":"REMEDY:%s:1:0:REFUND",
                  "risk_level":"HIGH",
                  "requires_approval":true,
                  "parameters":{}}]
                """
                        .formatted(caseId);
        String approvedPlan =
                """
                {"id":"%s","version":1,"actions":%s,
                 "preconditions":["PLATFORM_REVIEW_APPROVED"],
                 "notifications":[]}
                """
                        .formatted(planId, actionJson);
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + suffix,
                        null,
                        "user-" + suffix,
                        "merchant-" + suffix,
                        "CREATE_" + suffix,
                        "REFUND_REQUEST",
                        "Closure " + suffix,
                        "Closure integration test",
                        RiskLevel.HIGH,
                        "user-" + suffix);
        disputeCase.completeIntake(
                "REFUND_DISPUTE",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                "{}",
                "user-" + suffix);
        disputeCase.markDossierBuilt("user-" + suffix);
        disputeCase.applyRoute(
                RouteType.SIMPLE_HEARING, "user-" + suffix);
        disputeCase.markRemedyPlanned("temporal-worker");
        disputeCase.waitForHumanReview("temporal-worker");
        disputeCase.applyReviewOutcome(
                ApprovalDecisionType.APPROVE, "reviewer-" + suffix);
        disputeCase.beginExecution("temporal-worker");
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
                        "[]",
                        "temporal-worker"));
        packets.saveAndFlush(
                ReviewPacketEntity.create(
                        packetId,
                        caseId,
                        planId,
                        1,
                        "{}",
                        "[]",
                        "[]",
                        "[]",
                        "{}",
                        approvedPlan,
                        "[]",
                        "temporal-worker"));
        ReviewTaskEntity task =
                ReviewTaskEntity.pending(
                        taskId,
                        caseId,
                        planId,
                        packetId,
                        "URGENT",
                        ActorRole.PLATFORM_REVIEWER.name(),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                        "temporal-worker");
        task.decide(
                ApprovalDecisionType.APPROVE,
                "reviewer-" + suffix,
                "{\"decision\":\"APPROVE\"}");
        reviewTasks.saveAndFlush(task);
        approvals.saveAndFlush(
                ApprovalRecordEntity.record(
                        approvalId,
                        caseId,
                        taskId,
                        planId,
                        "reviewer-" + suffix,
                        ActorRole.PLATFORM_REVIEWER.name(),
                        ApprovalDecisionType.APPROVE,
                        approvedPlan,
                        approvedPlan,
                        "approved",
                        "HASH_" + suffix));
        ActionRecordEntity action =
                ActionRecordEntity.running(
                        "ACTION_" + suffix,
                        caseId,
                        planId,
                        approvalId,
                        "REFUND",
                        RiskLevel.HIGH,
                        "REMEDY:" + caseId + ":1:0:REFUND",
                        "reviewer-" + suffix,
                        "temporal-worker",
                        actionJson);
        if (succeeded) {
            action.succeed("{\"status\":\"SUCCEEDED\"}");
        } else {
            action.fail(
                    "TOOL_EXECUTION_FAILED",
                    "simulated",
                    "{\"status\":\"FAILED\"}");
        }
        actions.saveAndFlush(action);
    }
}
