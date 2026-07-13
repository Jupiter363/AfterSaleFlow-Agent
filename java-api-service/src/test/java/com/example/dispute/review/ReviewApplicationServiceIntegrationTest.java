/*
 * 所属模块：平台人工终审。
 * 文件职责：验证审核应用Integration，覆盖 「createsPacketAndOnlyReviewerCanModifyApproveWithDiff」、「rejectsAPlatformReviewerWhoseIdentityIsNotTheSystemReviewer」、「anotherPlatformReviewerRetainsReadOnlyReviewAccess」、「requestsSupplementThroughLifecycleNotifications」、「announcesManualHandoffWhenTheReviewerEscalates」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.ReviewTaskStatus;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalPolicyDecisionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.review.application.PostReviewOrchestrationResult;
import com.example.dispute.review.application.PostReviewOrchestrationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.review.application.ReviewDecisionCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【平台人工终审 / 自动化测试层】类型「ReviewApplicationServiceIntegrationTest」。
// 类型职责：集中验证审核应用Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「properties」、「seed」、「createsPacketAndOnlyReviewerCanModifyApproveWithDiff」、「rejectsAPlatformReviewerWhoseIdentityIsNotTheSystemReviewer」、「anotherPlatformReviewerRetainsReadOnlyReviewAccess」、「requestsSupplementThroughLifecycleNotifications」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    ReviewApplicationService.class,
    ReviewApplicationServiceIntegrationTest.JacksonConfig.class
})
@Testcontainers
class ReviewApplicationServiceIntegrationTest {
    @Container static final GenericContainer<?> POSTGRESQL=new GenericContainer<>(DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
            .withEnv("POSTGRES_DB","dispute_review").withEnv("POSTGRES_USER","dispute_test").withEnv("POSTGRES_PASSWORD","local_test_password").withExposedPorts(5432);
    // 所属模块：【平台人工终审 / 自动化测试层】「ReviewApplicationServiceIntegrationTest.properties(DynamicPropertyRegistry)」。
    // 具体功能：「ReviewApplicationServiceIntegrationTest.properties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「properties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「ReviewApplicationServiceIntegrationTest.properties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「ReviewApplicationServiceIntegrationTest.properties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ReviewApplicationServiceIntegrationTest.properties(DynamicPropertyRegistry)」守住「平台人工终审」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:postgresql://"+POSTGRESQL.getHost()+":"+POSTGRESQL.getMappedPort(5432)+"/dispute_review");
        r.add("spring.datasource.username",()->"dispute_test");r.add("spring.datasource.password",()->"local_test_password");
    }
    // 所属模块：【平台人工终审 / 自动化测试层】类型「JacksonConfig」。
    // 类型职责：承载JacksonConfig在当前业务模块中的规则与协作边界；本类型显式提供 「objectMapper」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration static class JacksonConfig{
        // 所属模块：【平台人工终审 / 自动化测试层】「ReviewApplicationServiceIntegrationTest.JacksonConfig.objectMapper()」。
        // 具体功能：「ReviewApplicationServiceIntegrationTest.JacksonConfig.objectMapper()」：作为测试辅助方法为“核对完整业务行为（场景方法「objectMapper」）”组装或读取「ObjectMapper」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「ReviewApplicationServiceIntegrationTest.JacksonConfig.objectMapper()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「ReviewApplicationServiceIntegrationTest.JacksonConfig.objectMapper()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「ReviewApplicationServiceIntegrationTest.JacksonConfig.objectMapper()」守住「平台人工终审」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean ObjectMapper objectMapper(){return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);}}
    @Autowired ReviewApplicationService service; @Autowired FulfillmentCaseRepository cases;
    @Autowired RemedyPlanRepository plans; @Autowired ReviewTaskRepository tasks;
    @Autowired ReviewPacketRepository packets; @Autowired ApprovalRecordRepository approvals;
    @Autowired ApprovalPolicyDecisionRepository policyDecisions;
    @MockitoBean AuditRecorder audit;
    @MockitoBean PostReviewOrchestrationService postReviewOrchestration;
    @MockitoBean CaseLifecycleNotificationService lifecycleNotifications;

    // 所属模块：【平台人工终审 / 自动化测试层】「ReviewApplicationServiceIntegrationTest.seed()」。
    // 具体功能：「ReviewApplicationServiceIntegrationTest.seed()」：在每个测试场景运行前创建「FulfillmentCaseEntity.create」、「RemedyPlanEntity.pendingApproval」、「postReviewOrchestration.orchestrate」、「invocation.getArgument」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「ReviewApplicationServiceIntegrationTest.seed()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「ReviewApplicationServiceIntegrationTest.seed()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ReviewApplicationServiceIntegrationTest.seed()」守住「平台人工终审」的可执行规格，尤其防止 「CASE_review」、「CLOSED」、「ORDER_review」、「user-review」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach void seed(){
        when(postReviewOrchestration.orchestrate(anyString(), any(), anyString()))
                .thenAnswer(invocation -> new PostReviewOrchestrationResult(
                        invocation.getArgument(0),
                        "CASE_review",
                        "CLOSED",
                        true,
                        true,
                        "test orchestration"));
        FulfillmentCaseEntity c=FulfillmentCaseEntity.create("CASE_review","ORDER_review",null,"user-review","merchant-review","CREATE_review","REFUND_REQUEST","Review refund","Refund requires platform review",RiskLevel.HIGH,"user-review");
        c.completeIntake("ITEM_SWAP_DISPUTE",CaseStatus.INTAKE_COMPLETED,RiskLevel.HIGH,"{}","user-review");
        c.markDossierBuilt("user-review");c.applyRoute(RouteType.SIMPLE_HEARING,"user-review");c.markRemedyPlanned("temporal-worker");cases.saveAndFlush(c);
        plans.saveAndFlush(RemedyPlanEntity.pendingApproval("REMEDY_review",c.getId(),null,1,RouteType.SIMPLE_HEARING,RiskLevel.HIGH,
                "[{\"action_type\":\"REFUND\",\"idempotency_key\":\"REMEDY:CASE_review:1:0:REFUND\",\"preconditions\":[\"PLATFORM_REVIEW_APPROVED\"],\"risk_level\":\"HIGH\",\"requires_approval\":true,\"parameters\":{}}]",
                "[\"PLATFORM_REVIEW_APPROVED\"]","[\"NOTIFY_USER_AFTER_EXECUTION\"]","temporal-worker"));
    }

    @Test
    void startingReviewMovesTheTaskAndFullHearingCaseFromDraftToReview() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.create(
                        "CASE_review_start",
                        "ORDER_review_start",
                        null,
                        "user-review-start",
                        "merchant-review-start",
                        "CREATE_review_start",
                        "CONDITION_MISMATCH",
                        "Review a completed hearing draft",
                        "The parties can read the draft before the reviewer starts work.",
                        RiskLevel.HIGH,
                        "user-review-start");
        dispute.completeIntake(
                "CONDITION_MISMATCH",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                "{}",
                "user-review-start");
        dispute.markDossierBuilt("user-review-start");
        dispute.applyRoute(RouteType.FULL_HEARING, "user-review-start");
        dispute.markRemedyPlanned("temporal-worker");
        cases.saveAndFlush(dispute);
        plans.saveAndFlush(
                RemedyPlanEntity.pendingApproval(
                        "REMEDY_review_start",
                        dispute.getId(),
                        null,
                        1,
                        RouteType.FULL_HEARING,
                        RiskLevel.HIGH,
                        "[{\"action_type\":\"REFUND\",\"idempotency_key\":\"REMEDY:CASE_review_start:1:0:REFUND\",\"preconditions\":[\"PLATFORM_REVIEW_APPROVED\"],\"risk_level\":\"HIGH\",\"requires_approval\":true,\"parameters\":{}}]",
                        "[\"PLATFORM_REVIEW_APPROVED\"]",
                        "[\"NOTIFY_USER_AFTER_EXECUTION\"]",
                        "temporal-worker"));

        String taskId =
                service.createForWorkflow(
                        "CASE_review_start", "REMEDY_review_start");

        assertThat(tasks.findById(taskId))
                .hasValueSatisfying(
                        task ->
                                assertThat(task.getTaskStatus())
                                        .isEqualTo(ReviewTaskStatus.PENDING));
        assertThat(cases.findById("CASE_review_start"))
                .hasValueSatisfying(
                        persistedCase -> {
                            assertThat(persistedCase.getCaseStatus())
                                    .isEqualTo(CaseStatus.WAITING_HUMAN_REVIEW);
                            assertThat(persistedCase.getCurrentRoom()).isEqualTo("DRAFT");
                        });

        var started =
                service.start(
                        taskId,
                        new AuthenticatedActor(
                                "reviewer-local", ActorRole.PLATFORM_REVIEWER));
        tasks.flush();
        cases.flush();

        assertThat(started.status()).isEqualTo("IN_REVIEW");
        assertThat(started.assignedReviewerId()).isEqualTo("reviewer-local");
        assertThat(tasks.findById(taskId))
                .hasValueSatisfying(
                        task ->
                                assertThat(task.getTaskStatus())
                                        .isEqualTo(ReviewTaskStatus.IN_REVIEW));
        assertThat(cases.findById("CASE_review_start"))
                .hasValueSatisfying(
                        persistedCase -> {
                            assertThat(persistedCase.getCaseStatus())
                                    .isEqualTo(CaseStatus.WAITING_HUMAN_REVIEW);
                            assertThat(persistedCase.getCurrentRoom()).isEqualTo("REVIEW");
                        });
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff()」。
    // 具体功能：「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff()」：复现“创建并持久化（场景方法「createsPacketAndOnlyReviewerCanModifyApproveWithDiff」）”场景：驱动 「service.createForWorkflow」、「service.packet」、「service.list」、「service.decide」，再用 「assertThat」、「verify」、「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_review」、「REMEDY_review」、「reviewer-local」、「cs-1」。
    // 上游调用：「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify、assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff()」守住「平台人工终审」的可执行规格，尤其防止 「CASE_review」、「REMEDY_review」、「reviewer-local」、「cs-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test void createsPacketAndOnlyReviewerCanModifyApproveWithDiff(){
        String taskId=service.createForWorkflow("CASE_review","REMEDY_review");
        assertThat(tasks.findById(taskId))
                .hasValueSatisfying(
                        task ->
                                assertThat(task.getAssignedReviewerId())
                                        .isEqualTo("reviewer-local"));
        verify(lifecycleNotifications)
                .reviewPending(
                        any(FulfillmentCaseEntity.class),
                        eq(taskId));
        var packet=service.packet(taskId,new AuthenticatedActor("cs-1",ActorRole.CUSTOMER_SERVICE));
        assertThat(packet.remedy().path("actions")).hasSize(1);
        assertThat(service.list(ReviewTaskStatus.PENDING,new AuthenticatedActor("reviewer-local",ActorRole.PLATFORM_REVIEWER))).hasSize(1);
        var approved=packet.remedy().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) approved)
                .put("reviewer_note","amount verified");
        assertThatThrownBy(()->service.decide(taskId,new ReviewDecisionCommand(ApprovalDecisionType.APPROVE,"approve",null,"cs-key"),new AuthenticatedActor("cs-1",ActorRole.CUSTOMER_SERVICE))).isInstanceOf(ForbiddenException.class);
        var result=service.decide(taskId,new ReviewDecisionCommand(ApprovalDecisionType.MODIFY_AND_APPROVE,"amount verified",approved,"review-key"),new AuthenticatedActor("reviewer-local",ActorRole.PLATFORM_REVIEWER));
        verify(lifecycleNotifications)
                .finalDecision(
                        any(FulfillmentCaseEntity.class),
                        eq("MODIFY_AND_APPROVE"));
        assertThat(result.executionAllowed()).isTrue();
        assertThat(cases.findById("CASE_review")).hasValueSatisfying(c->assertThat(c.getCaseStatus()).isEqualTo(CaseStatus.APPROVED_FOR_EXECUTION));
        assertThat(approvals.findAllByCaseIdOrderByCreatedAtAsc("CASE_review")).singleElement().satisfies(record->{
            assertThat(record.getOriginalPlanJson()).contains("REFUND");
            assertThat(record.getApprovedPlanJson()).contains("amount verified");
            assertThat(record.getReviewPacketId()).isEqualTo(packet.id());
            assertThat(record.getReviewPacketVersion()).isEqualTo(packet.packetVersion());
            assertThat(record.getPolicyVersion()).isEqualTo("approval-policy-v1");
            assertThat(record.getActionSnapshotHash()).isNotBlank();
        });
        assertThat(packet.caseVersion()).isPositive();
        assertThat(packet.dossierVersion()).isPositive();
        assertThat(packet.promptVersion()).isEqualTo("hearing-v1");
        assertThat(packet.actionHash()).isNotBlank();
        assertThat(packet.expiresAt()).isAfter(packet.frozenAt());
        assertThat(policyDecisions
                        .findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(
                                "CASE_review", "REMEDY_review"))
                .isPresent();
        var retry =
                service.decide(
                        taskId,
                        new ReviewDecisionCommand(
                                ApprovalDecisionType.MODIFY_AND_APPROVE,
                                "amount verified",
                                approved,
                                "review-key"),
                        new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER));
        assertThat(retry.approvalRecordId()).isEqualTo(result.approvalRecordId());
        assertThat(approvals.findAllByCaseIdOrderByCreatedAtAsc("CASE_review")).hasSize(1);
        assertThatThrownBy(() -> service.decide(
                        taskId,
                        new ReviewDecisionCommand(
                                ApprovalDecisionType.REQUEST_MORE_EVIDENCE,
                                "reuse key with different payload",
                                null,
                                "review-key"),
                        new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER)))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(postReviewOrchestration, times(2))
                .orchestrate(
                        eq(result.approvalRecordId()),
                        any(AuthenticatedActor.class),
                        eq("review-key"));
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「ReviewApplicationServiceIntegrationTest.rejectsAPlatformReviewerWhoseIdentityIsNotTheSystemReviewer()」。
    // 具体功能：「ReviewApplicationServiceIntegrationTest.rejectsAPlatformReviewerWhoseIdentityIsNotTheSystemReviewer()」：复现“拒绝非法输入或越权操作（场景方法「rejectsAPlatformReviewerWhoseIdentityIsNotTheSystemReviewer」）”场景：驱动 「service.decide」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REVIEW_missing」、「reviewer-identity」、「reviewer-1」。
    // 上游调用：「ReviewApplicationServiceIntegrationTest.rejectsAPlatformReviewerWhoseIdentityIsNotTheSystemReviewer()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ReviewApplicationServiceIntegrationTest.rejectsAPlatformReviewerWhoseIdentityIsNotTheSystemReviewer()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ReviewApplicationServiceIntegrationTest.rejectsAPlatformReviewerWhoseIdentityIsNotTheSystemReviewer()」守住「平台人工终审」的可执行规格，尤其防止 「REVIEW_missing」、「reviewer-identity」、「reviewer-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsAPlatformReviewerWhoseIdentityIsNotTheSystemReviewer() {
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        "REVIEW_missing",
                                        new ReviewDecisionCommand(
                                                ApprovalDecisionType.APPROVE,
                                                "attempted by another reviewer",
                                                null,
                                                "reviewer-identity"),
                                        new AuthenticatedActor(
                                                "reviewer-1",
                                                ActorRole.PLATFORM_REVIEWER)))
                .isInstanceOf(ForbiddenException.class);
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「ReviewApplicationServiceIntegrationTest.anotherPlatformReviewerRetainsReadOnlyReviewAccess()」。
    // 具体功能：「ReviewApplicationServiceIntegrationTest.anotherPlatformReviewerRetainsReadOnlyReviewAccess()」：复现“核对完整业务行为（场景方法「anotherPlatformReviewerRetainsReadOnlyReviewAccess」）”场景：驱动 「service.createForWorkflow」、「service.list」、「service.packet」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_review」、「REMEDY_review」、「reviewer-1」。
    // 上游调用：「ReviewApplicationServiceIntegrationTest.anotherPlatformReviewerRetainsReadOnlyReviewAccess()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ReviewApplicationServiceIntegrationTest.anotherPlatformReviewerRetainsReadOnlyReviewAccess()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ReviewApplicationServiceIntegrationTest.anotherPlatformReviewerRetainsReadOnlyReviewAccess()」守住「平台人工终审」的可执行规格，尤其防止 「CASE_review」、「REMEDY_review」、「reviewer-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void anotherPlatformReviewerRetainsReadOnlyReviewAccess() {
        String taskId = service.createForWorkflow("CASE_review", "REMEDY_review");
        AuthenticatedActor anotherReviewer =
                new AuthenticatedActor(
                        "reviewer-1",
                        ActorRole.PLATFORM_REVIEWER);

        assertThat(service.list(ReviewTaskStatus.PENDING, anotherReviewer))
                .extracting(task -> task.id())
                .contains(taskId);
        assertThat(service.packet(taskId, anotherReviewer).caseId())
                .isEqualTo("CASE_review");
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「ReviewApplicationServiceIntegrationTest.requestsSupplementThroughLifecycleNotifications()」。
    // 具体功能：「ReviewApplicationServiceIntegrationTest.requestsSupplementThroughLifecycleNotifications()」：复现“核对完整业务行为（场景方法「requestsSupplementThroughLifecycleNotifications」）”场景：驱动 「service.createForWorkflow」、「service.decide」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_review」、「REMEDY_review」、「需要补充签收证明」、「review-supplement」。
    // 上游调用：「ReviewApplicationServiceIntegrationTest.requestsSupplementThroughLifecycleNotifications()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ReviewApplicationServiceIntegrationTest.requestsSupplementThroughLifecycleNotifications()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ReviewApplicationServiceIntegrationTest.requestsSupplementThroughLifecycleNotifications()」守住「平台人工终审」的可执行规格，尤其防止 「CASE_review」、「REMEDY_review」、「需要补充签收证明」、「review-supplement」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void requestsSupplementThroughLifecycleNotifications() {
        String supplementTask =
                service.createForWorkflow("CASE_review", "REMEDY_review");
        service.decide(
                supplementTask,
                new ReviewDecisionCommand(
                        ApprovalDecisionType.REQUEST_MORE_EVIDENCE,
                        "需要补充签收证明",
                        null,
                        "review-supplement"),
                new AuthenticatedActor(
                        "reviewer-local", ActorRole.PLATFORM_REVIEWER));

        verify(lifecycleNotifications)
                .supplementRequested(
                        any(FulfillmentCaseEntity.class),
                        eq("review-" + supplementTask));
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「ReviewApplicationServiceIntegrationTest.announcesManualHandoffWhenTheReviewerEscalates()」。
    // 具体功能：「ReviewApplicationServiceIntegrationTest.announcesManualHandoffWhenTheReviewerEscalates()」：复现“核对完整业务行为（场景方法「announcesManualHandoffWhenTheReviewerEscalates」）”场景：驱动 「service.createForWorkflow」、「service.decide」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_review」、「REMEDY_review」、「复杂争议转人工专员」、「review-manual」。
    // 上游调用：「ReviewApplicationServiceIntegrationTest.announcesManualHandoffWhenTheReviewerEscalates()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ReviewApplicationServiceIntegrationTest.announcesManualHandoffWhenTheReviewerEscalates()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ReviewApplicationServiceIntegrationTest.announcesManualHandoffWhenTheReviewerEscalates()」守住「平台人工终审」的可执行规格，尤其防止 「CASE_review」、「REMEDY_review」、「复杂争议转人工专员」、「review-manual」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void announcesManualHandoffWhenTheReviewerEscalates() {
        String taskId =
                service.createForWorkflow("CASE_review", "REMEDY_review");
        service.decide(
                taskId,
                new ReviewDecisionCommand(
                        ApprovalDecisionType.ESCALATE_MANUAL,
                        "复杂争议转人工专员",
                        null,
                        "review-manual"),
                new AuthenticatedActor(
                        "reviewer-local", ActorRole.PLATFORM_REVIEWER));

        verify(lifecycleNotifications)
                .manualHandoff(
                        any(FulfillmentCaseEntity.class),
                        eq("ESCALATE_MANUAL"));
    }
}
