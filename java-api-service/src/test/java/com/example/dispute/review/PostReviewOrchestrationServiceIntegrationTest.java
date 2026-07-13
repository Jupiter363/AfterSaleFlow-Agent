/*
 * 所属模块：平台人工终审。
 * 文件职责：验证事务后审核OrchestrationIntegration，覆盖 「approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions」、「requestMoreEvidenceDoesNotExecuteActions」、「manualHandoffDoesNotExecuteActions」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
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
import com.example.dispute.review.application.PostReviewOrchestrationService;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.review.domain.ReviewPacketVersions;
import com.example.dispute.room.application.CaseEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

// 所属模块：【平台人工终审 / 自动化测试层】类型「PostReviewOrchestrationServiceIntegrationTest」。
// 类型职责：集中验证事务后审核OrchestrationIntegration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「properties」、「resetDataAndMocks」、「approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions」、「requestMoreEvidenceDoesNotExecuteActions」、「manualHandoffDoesNotExecuteActions」、「seed」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    PostReviewOrchestrationService.class,
    PostReviewOrchestrationServiceIntegrationTest.JacksonConfig.class
})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostReviewOrchestrationServiceIntegrationTest {

    private static final AuthenticatedActor REVIEWER =
            new AuthenticatedActor("reviewer-post-review", ActorRole.PLATFORM_REVIEWER);

    @Container
    static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_post_review")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【平台人工终审 / 自动化测试层】「PostReviewOrchestrationServiceIntegrationTest.properties(DynamicPropertyRegistry)」。
    // 具体功能：「PostReviewOrchestrationServiceIntegrationTest.properties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「properties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「PostReviewOrchestrationServiceIntegrationTest.properties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「PostReviewOrchestrationServiceIntegrationTest.properties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「PostReviewOrchestrationServiceIntegrationTest.properties(DynamicPropertyRegistry)」守住「平台人工终审」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_post_review");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    // 所属模块：【平台人工终审 / 自动化测试层】类型「JacksonConfig」。
    // 类型职责：承载JacksonConfig在当前业务模块中的规则与协作边界；本类型显式提供 「objectMapper」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration
    static class JacksonConfig {
        // 所属模块：【平台人工终审 / 自动化测试层】「PostReviewOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」。
        // 具体功能：「PostReviewOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」：作为测试辅助方法为“核对完整业务行为（场景方法「objectMapper」）”组装或读取「ObjectMapper」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「PostReviewOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「PostReviewOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「PostReviewOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」守住「平台人工终审」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .findAndRegisterModules()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
    }

    @Autowired PostReviewOrchestrationService service;
    @Autowired FulfillmentCaseRepository cases;
    @Autowired RemedyPlanRepository plans;
    @Autowired ReviewPacketRepository packets;
    @Autowired ReviewTaskRepository reviewTasks;
    @Autowired ApprovalRecordRepository approvals;
    @Autowired ActionRecordRepository actions;
    @Autowired EvaluationTraceRepository evaluations;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AuditRecorder auditRecorder;
    @MockitoBean CaseEventService caseEventService;

    // 所属模块：【平台人工终审 / 自动化测试层】「PostReviewOrchestrationServiceIntegrationTest.resetDataAndMocks()」。
    // 具体功能：「PostReviewOrchestrationServiceIntegrationTest.resetDataAndMocks()」：在每个测试场景运行前创建「actions.deleteAllInBatch」、「evaluations.deleteAllInBatch」、「approvals.deleteAllInBatch」、「reviewTasks.deleteAllInBatch」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「PostReviewOrchestrationServiceIntegrationTest.resetDataAndMocks()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「PostReviewOrchestrationServiceIntegrationTest.resetDataAndMocks()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「PostReviewOrchestrationServiceIntegrationTest.resetDataAndMocks()」守住「平台人工终审」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void resetDataAndMocks() {
        actions.deleteAllInBatch();
        evaluations.deleteAllInBatch();
        approvals.deleteAllInBatch();
        reviewTasks.deleteAllInBatch();
        packets.deleteAllInBatch();
        plans.deleteAllInBatch();
        cases.deleteAllInBatch();
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「PostReviewOrchestrationServiceIntegrationTest.approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions()」。
    // 具体功能：「PostReviewOrchestrationServiceIntegrationTest.approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions()」：复现“核对完整业务行为（场景方法「approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions」）”场景：驱动 「service.orchestrate」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「approved」、「APPROVAL_approved」、「post-review-approved」、「EXECUTION_ASSISTANT_HANDOFF」。
    // 上游调用：「PostReviewOrchestrationServiceIntegrationTest.approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「PostReviewOrchestrationServiceIntegrationTest.approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「PostReviewOrchestrationServiceIntegrationTest.approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions()」守住「平台人工终审」的可执行规格，尤其防止 「approved」、「APPROVAL_approved」、「post-review-approved」、「EXECUTION_ASSISTANT_HANDOFF」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions() {
        seed("approved", ApprovalDecisionType.APPROVE);

        var result =
                service.orchestrate(
                        "APPROVAL_approved", REVIEWER, "post-review-approved");

        assertThat(result.status()).isEqualTo("EXECUTION_ASSISTANT_HANDOFF");
        assertThat(result.executionAttempted()).isFalse();
        assertThat(result.closureAttempted()).isFalse();
        assertThat(result.message())
                .isEqualTo("final decision confirmed and handed off to execution assistant");
        assertThat(cases.findById("CASE_approved"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(CaseStatus.APPROVED_FOR_EXECUTION));
        assertThat(actions.findAllByCaseIdOrderByCreatedAtAsc("CASE_approved"))
                .isEmpty();
        assertThat(evaluations.findAll()).isEmpty();
        verify(caseEventService)
                .recordLifecycleEvent(
                        eq("CASE_approved"),
                        isNull(),
                        eq("EXECUTION_ASSISTANT_HANDOFF"),
                        argThat(
                                payload ->
                                        "APPROVAL_approved"
                                                        .equals(payload.get("approval_record_id"))
                                                && "APPROVE".equals(payload.get("decision"))
                                                && "EXECUTION_ASSISTANT_HANDOFF"
                                                        .equals(payload.get("status"))
                                                && REVIEWER.actorId()
                                                        .equals(payload.get("reviewer_id"))),
                        eq("post-review-execution-assistant:APPROVAL_approved"),
                        eq(REVIEWER.actorId()));
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「PostReviewOrchestrationServiceIntegrationTest.requestMoreEvidenceDoesNotExecuteActions()」。
    // 具体功能：「PostReviewOrchestrationServiceIntegrationTest.requestMoreEvidenceDoesNotExecuteActions()」：复现“核对完整业务行为（场景方法「requestMoreEvidenceDoesNotExecuteActions」）”场景：驱动 「service.orchestrate」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「supplement」、「APPROVAL_supplement」、「post-review-supplement」、「WAITING_EVIDENCE」。
    // 上游调用：「PostReviewOrchestrationServiceIntegrationTest.requestMoreEvidenceDoesNotExecuteActions()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「PostReviewOrchestrationServiceIntegrationTest.requestMoreEvidenceDoesNotExecuteActions()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「PostReviewOrchestrationServiceIntegrationTest.requestMoreEvidenceDoesNotExecuteActions()」守住「平台人工终审」的可执行规格，尤其防止 「supplement」、「APPROVAL_supplement」、「post-review-supplement」、「WAITING_EVIDENCE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void requestMoreEvidenceDoesNotExecuteActions() {
        seed("supplement", ApprovalDecisionType.REQUEST_MORE_EVIDENCE);

        var result =
                service.orchestrate(
                        "APPROVAL_supplement", REVIEWER, "post-review-supplement");

        assertThat(result.status()).isEqualTo("WAITING_EVIDENCE");
        assertThat(result.executionAttempted()).isFalse();
        assertThat(result.closureAttempted()).isFalse();
        assertThat(actions.findAllByCaseIdOrderByCreatedAtAsc("CASE_supplement"))
                .isEmpty();
        assertThat(evaluations.findAll()).isEmpty();
        assertThat(cases.findById("CASE_supplement"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(CaseStatus.WAITING_EVIDENCE));
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「PostReviewOrchestrationServiceIntegrationTest.manualHandoffDoesNotExecuteActions()」。
    // 具体功能：「PostReviewOrchestrationServiceIntegrationTest.manualHandoffDoesNotExecuteActions()」：复现“核对完整业务行为（场景方法「manualHandoffDoesNotExecuteActions」）”场景：驱动 「service.orchestrate」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「manual」、「APPROVAL_manual」、「post-review-manual」、「MANUAL_HANDOFF」。
    // 上游调用：「PostReviewOrchestrationServiceIntegrationTest.manualHandoffDoesNotExecuteActions()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「PostReviewOrchestrationServiceIntegrationTest.manualHandoffDoesNotExecuteActions()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「PostReviewOrchestrationServiceIntegrationTest.manualHandoffDoesNotExecuteActions()」守住「平台人工终审」的可执行规格，尤其防止 「manual」、「APPROVAL_manual」、「post-review-manual」、「MANUAL_HANDOFF」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void manualHandoffDoesNotExecuteActions() {
        seed("manual", ApprovalDecisionType.ESCALATE_MANUAL);

        var result =
                service.orchestrate(
                        "APPROVAL_manual", REVIEWER, "post-review-manual");

        assertThat(result.status()).isEqualTo("MANUAL_HANDOFF");
        assertThat(result.executionAttempted()).isFalse();
        assertThat(result.closureAttempted()).isFalse();
        assertThat(actions.findAllByCaseIdOrderByCreatedAtAsc("CASE_manual"))
                .isEmpty();
        assertThat(evaluations.findAll()).isEmpty();
        assertThat(cases.findById("CASE_manual"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(CaseStatus.MANUAL_HANDOFF));
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「PostReviewOrchestrationServiceIntegrationTest.seed(String,ApprovalDecisionType)」。
    // 具体功能：「PostReviewOrchestrationServiceIntegrationTest.seed(String,ApprovalDecisionType)」：作为测试辅助方法为“核对完整业务行为（场景方法「seed」）”组装或读取「ReviewPacketVersions」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「PostReviewOrchestrationServiceIntegrationTest.seed(String,ApprovalDecisionType)」由本测试类中的 「PostReviewOrchestrationServiceIntegrationTest.approvedReviewHandsOffToExecutionAssistantWithoutExecutingActions」、「PostReviewOrchestrationServiceIntegrationTest.requestMoreEvidenceDoesNotExecuteActions」、「PostReviewOrchestrationServiceIntegrationTest.manualHandoffDoesNotExecuteActions」 调用。
    // 下游影响：「PostReviewOrchestrationServiceIntegrationTest.seed(String,ApprovalDecisionType)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「PostReviewOrchestrationServiceIntegrationTest.seed(String,ApprovalDecisionType)」守住「平台人工终审」的可执行规格，尤其防止 「CASE_」、「REMEDY_」、「PACKET_」、「REVIEW_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seed(String suffix, ApprovalDecisionType decision) {
        String caseId = "CASE_" + suffix;
        String planId = "REMEDY_" + suffix;
        String packetId = "PACKET_" + suffix;
        String taskId = "REVIEW_" + suffix;
        String approvalId = "APPROVAL_" + suffix;
        String actionJson =
                """
                [{"action_type":"RESHIP",
                  "idempotency_key":"REMEDY:%s:1:0:RESHIP",
                  "preconditions":["PLATFORM_REVIEW_APPROVED"],
                  "risk_level":"HIGH",
                  "requires_approval":true,
                  "parameters":{}}]
                """
                        .formatted(caseId);
        String notifications = "[\"NOTIFY_USER_AFTER_EXECUTION\"]";
        String approvedPlan =
                """
                {"id":"%s","version":1,"actions":%s,
                 "preconditions":["PLATFORM_REVIEW_APPROVED"],
                 "notifications":%s}
                """
                        .formatted(planId, actionJson, notifications);
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + suffix,
                        null,
                        "user-" + suffix,
                        "merchant-" + suffix,
                        "CREATE_" + suffix,
                        "DISPUTE",
                        "Post review " + suffix,
                        "Post review integration test",
                        RiskLevel.HIGH,
                        "user-" + suffix);
        disputeCase.completeIntake(
                "ITEM_SWAP_DISPUTE",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                "{}",
                "user-" + suffix);
        disputeCase.markDossierBuilt("user-" + suffix);
        disputeCase.applyRoute(RouteType.FULL_HEARING, "user-" + suffix);
        disputeCase.markRemedyPlanned("temporal-worker");
        disputeCase.waitForHumanReview("temporal-worker");
        disputeCase.applyReviewOutcome(decision, "reviewer-" + suffix);
        cases.saveAndFlush(disputeCase);
        plans.saveAndFlush(
                RemedyPlanEntity.pendingApproval(
                        planId,
                        caseId,
                        null,
                        1,
                        RouteType.FULL_HEARING,
                        RiskLevel.HIGH,
                        actionJson,
                        "[\"PLATFORM_REVIEW_APPROVED\"]",
                        notifications,
                        "temporal-worker"));
        OffsetDateTime frozenAt = OffsetDateTime.now(ZoneOffset.UTC);
        JsonNode approvedPlanNode = read(approvedPlan);
        String actionHash = ActionSnapshotHasher.hash(objectMapper, approvedPlanNode);
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
        ReviewTaskEntity task =
                ReviewTaskEntity.pending(
                        taskId,
                        caseId,
                        planId,
                        packetId,
                        "URGENT",
                        ActorRole.PLATFORM_REVIEWER.name(),
                        frozenAt.plusDays(1),
                        "temporal-worker");
        task.decide(decision, "reviewer-" + suffix, "{\"decision\":\"" + decision + "\"}");
        reviewTasks.saveAndFlush(task);
        approvals.saveAndFlush(
                ApprovalRecordEntity.recordFrozen(
                        approvalId,
                        caseId,
                        taskId,
                        planId,
                        "reviewer-" + suffix,
                        ActorRole.PLATFORM_REVIEWER.name(),
                        decision,
                        approvedPlan,
                        approvedPlan,
                        "approved",
                        "HASH_" + suffix,
                        packetId,
                        1,
                        "approval-policy-v1",
                        actionHash,
                        frozenAt.plusDays(1)));
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「PostReviewOrchestrationServiceIntegrationTest.read(String)」。
    // 具体功能：「PostReviewOrchestrationServiceIntegrationTest.read(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「read」）”组装或读取「IllegalStateException」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「PostReviewOrchestrationServiceIntegrationTest.read(String)」由本测试类中的 「PostReviewOrchestrationServiceIntegrationTest.seed」 调用。
    // 下游影响：「PostReviewOrchestrationServiceIntegrationTest.read(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「PostReviewOrchestrationServiceIntegrationTest.read(String)」守住「平台人工终审」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
