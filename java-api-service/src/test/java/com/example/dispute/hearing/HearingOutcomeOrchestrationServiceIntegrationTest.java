/*
 * 所属模块：共享小法庭。
 * 文件职责：验证庭审结果OrchestrationIntegration，覆盖 「recoversCompletedHearingDraftIntoReviewGateIdempotently」、「batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.hearing.application.HearingOutcomeOrchestrationService;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.PostReviewOrchestrationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【共享小法庭 / 自动化测试层】类型「HearingOutcomeOrchestrationServiceIntegrationTest」。
// 类型职责：集中验证庭审结果OrchestrationIntegration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「recoversCompletedHearingDraftIntoReviewGateIdempotently」、「batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes」、「seedLegacyCompletedHearingWithoutRoute」、「hearingIdFor」、「draftIdFor」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    HearingOutcomeOrchestrationService.class,
    RemedyApplicationService.class,
    ReviewApplicationService.class,
    HearingOutcomeOrchestrationServiceIntegrationTest.JacksonConfig.class
})
@Testcontainers
class HearingOutcomeOrchestrationServiceIntegrationTest {

    @Container
    static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_hearing_outcome")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingOutcomeOrchestrationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「HearingOutcomeOrchestrationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「HearingOutcomeOrchestrationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「HearingOutcomeOrchestrationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingOutcomeOrchestrationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「共享小法庭」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_hearing_outcome");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】类型「JacksonConfig」。
    // 类型职责：承载JacksonConfig在当前业务模块中的规则与协作边界；本类型显式提供 「objectMapper」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration
    static class JacksonConfig {
        // 所属模块：【共享小法庭 / 自动化测试层】「HearingOutcomeOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」。
        // 具体功能：「HearingOutcomeOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」：作为测试辅助方法为“核对完整业务行为（场景方法「objectMapper」）”组装或读取「ObjectMapper」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「HearingOutcomeOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「HearingOutcomeOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「HearingOutcomeOrchestrationServiceIntegrationTest.JacksonConfig.objectMapper()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .findAndRegisterModules()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
    }

    @Autowired HearingOutcomeOrchestrationService service;
    @Autowired FulfillmentCaseRepository cases;
    @Autowired HearingStateRepository hearings;
    @Autowired AdjudicationDraftRepository drafts;
    @Autowired RemedyPlanRepository remedies;
    @Autowired ReviewTaskRepository reviews;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @MockitoBean AuditRecorder auditRecorder;
    @MockitoBean CaseLifecycleNotificationService lifecycleNotifications;
    @MockitoBean PostReviewOrchestrationService postReviewOrchestration;

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingOutcomeOrchestrationServiceIntegrationTest.recoversCompletedHearingDraftIntoReviewGateIdempotently()」。
    // 具体功能：「HearingOutcomeOrchestrationServiceIntegrationTest.recoversCompletedHearingDraftIntoReviewGateIdempotently()」：复现“恢复中断状态（场景方法「recoversCompletedHearingDraftIntoReviewGateIdempotently」）”场景：驱动 「service.orchestrate」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_hearing_outcome_recovery」、「hearing-window-」、「test-recovery」。
    // 上游调用：「HearingOutcomeOrchestrationServiceIntegrationTest.recoversCompletedHearingDraftIntoReviewGateIdempotently()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingOutcomeOrchestrationServiceIntegrationTest.recoversCompletedHearingDraftIntoReviewGateIdempotently()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingOutcomeOrchestrationServiceIntegrationTest.recoversCompletedHearingDraftIntoReviewGateIdempotently()」守住「共享小法庭」的可执行规格，尤其防止 「CASE_hearing_outcome_recovery」、「hearing-window-」、「test-recovery」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void recoversCompletedHearingDraftIntoReviewGateIdempotently() {
        String caseId = "CASE_hearing_outcome_recovery";
        String workflowId = "hearing-window-" + caseId;
        seedLegacyCompletedHearingWithoutRoute(caseId, workflowId);

        var first = service.orchestrate(caseId, "test-recovery");
        var second = service.orchestrate(caseId, "test-recovery");

        assertThat(first.createdRemedy()).isTrue();
        assertThat(first.createdReviewTask()).isTrue();
        assertThat(second.createdRemedy()).isFalse();
        assertThat(second.createdReviewTask()).isFalse();
        assertThat(remedies.findFirstByCaseIdOrderByPlanVersionDesc(caseId))
                .hasValueSatisfying(
                        plan -> {
                            assertThat(plan.getSourceRoute())
                                    .isEqualTo(RouteType.FULL_HEARING);
                            assertThat(plan.getAdjudicationDraftId())
                                    .isEqualTo(draftIdFor(caseId));
                        });
        assertThat(reviews.findFirstByCaseIdOrderByCreatedAtDesc(caseId))
                .hasValueSatisfying(
                        task -> assertThat(task.getPlanId()).isEqualTo(first.remedyPlanId()));
        assertThat(cases.findById(caseId))
                .hasValueSatisfying(
                        dispute ->
                                assertThat(dispute.getCaseStatus())
                                        .isEqualTo(CaseStatus.WAITING_HUMAN_REVIEW));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingOutcomeOrchestrationServiceIntegrationTest.batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes()」。
    // 具体功能：「HearingOutcomeOrchestrationServiceIntegrationTest.batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes()」：复现“核对完整业务行为（场景方法「batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes」）”场景：驱动 「service.recoverCompletedHearingsWithoutReview」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「hearing-window-」、「CASE_hearing_recovery_batch_good」。
    // 上游调用：「HearingOutcomeOrchestrationServiceIntegrationTest.batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingOutcomeOrchestrationServiceIntegrationTest.batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingOutcomeOrchestrationServiceIntegrationTest.batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes()」守住「共享小法庭」的可执行规格，尤其防止 「hearing-window-」、「CASE_hearing_recovery_batch_good」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes() {
        String badCaseId = "CASE_hearing_recovery_missing_draft";
        seedLegacyCompletedHearingWithoutRoute(
                badCaseId, "hearing-window-" + badCaseId);
        jdbcTemplate.update("delete from adjudication_draft where case_id = ?", badCaseId);
        entityManager.clear();

        String goodCaseId = "CASE_hearing_recovery_batch_good";
        seedLegacyCompletedHearingWithoutRoute(
                goodCaseId, "hearing-window-" + goodCaseId);

        int recovered = service.recoverCompletedHearingsWithoutReview(10);

        assertThat(recovered).isEqualTo(1);
        assertThat(reviews.findFirstByCaseIdOrderByCreatedAtDesc(badCaseId)).isEmpty();
        assertThat(reviews.findFirstByCaseIdOrderByCreatedAtDesc(goodCaseId)).isPresent();
        assertThat(remedies.findFirstByCaseIdOrderByPlanVersionDesc(goodCaseId)).isPresent();
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingOutcomeOrchestrationServiceIntegrationTest.seedLegacyCompletedHearingWithoutRoute(String,String)」。
    // 具体功能：「HearingOutcomeOrchestrationServiceIntegrationTest.seedLegacyCompletedHearingWithoutRoute(String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「seedLegacyCompletedHearingWithoutRoute」）”组装或读取「BigDecimal」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「HearingOutcomeOrchestrationServiceIntegrationTest.seedLegacyCompletedHearingWithoutRoute(String,String)」由本测试类中的 「HearingOutcomeOrchestrationServiceIntegrationTest.recoversCompletedHearingDraftIntoReviewGateIdempotently」、「HearingOutcomeOrchestrationServiceIntegrationTest.batchRecoverySkipsBadCompletedHearingsAndContinuesWithHealthyOnes」 调用。
    // 下游影响：「HearingOutcomeOrchestrationServiceIntegrationTest.seedLegacyCompletedHearingWithoutRoute(String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingOutcomeOrchestrationServiceIntegrationTest.seedLegacyCompletedHearingWithoutRoute(String,String)」守住「共享小法庭」的可执行规格，尤其防止 「ORDER_」、「user-hearing-outcome」、「merchant-hearing-outcome」、「CREATE_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seedLegacyCompletedHearingWithoutRoute(
            String caseId, String workflowId) {
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + caseId,
                        null,
                        "user-hearing-outcome",
                        "merchant-hearing-outcome",
                        "CREATE_" + caseId,
                        "DISPUTE",
                        "Recovered hearing outcome",
                        "Completed hearing draft needs review recovery.",
                        RiskLevel.HIGH,
                        "user-hearing-outcome");
        disputeCase.completeIntake(
                "FULFILLMENT_CONFLICT",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                "{}",
                "user-hearing-outcome");
        disputeCase.markDossierBuilt("user-hearing-outcome");
        disputeCase.applyRoute(RouteType.FULL_HEARING, "user-hearing-outcome");
        disputeCase.startHearing(workflowId, "temporal-worker");
        cases.saveAndFlush(disputeCase);
        HearingStateEntity hearing =
                HearingStateEntity.start(
                        hearingIdFor(caseId),
                        caseId,
                        workflowId,
                        "temporal-worker");
        hearing.complete(true, "temporal-worker");
        hearings.saveAndFlush(hearing);
        drafts.saveAndFlush(
                AdjudicationDraftEntity.create(
                        draftIdFor(caseId),
                        caseId,
                        hearing.getId(),
                        1,
                        "[]",
                        "[]",
                        "[]",
                        "[\"review recovered draft\"]",
                        "MANUAL_REVIEW_REQUIRED",
                        new BigDecimal("0.1000"),
                        "Recovered draft should be frozen for reviewer approval.",
                        "python-agent-service/fallback",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));
        jdbcTemplate.update(
                "update fulfillment_dispute_case set hearing_route = null where id = ?",
                caseId);
        entityManager.clear();
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingOutcomeOrchestrationServiceIntegrationTest.hearingIdFor(String)」。
    // 具体功能：「HearingOutcomeOrchestrationServiceIntegrationTest.hearingIdFor(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「hearingIdFor」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
    // 上游调用：「HearingOutcomeOrchestrationServiceIntegrationTest.hearingIdFor(String)」由本测试类中的 「HearingOutcomeOrchestrationServiceIntegrationTest.seedLegacyCompletedHearingWithoutRoute」 调用。
    // 下游影响：「HearingOutcomeOrchestrationServiceIntegrationTest.hearingIdFor(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingOutcomeOrchestrationServiceIntegrationTest.hearingIdFor(String)」守住「共享小法庭」的可执行规格，尤其防止 「HEARING_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private String hearingIdFor(String caseId) {
        return "HEARING_" + caseId;
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「HearingOutcomeOrchestrationServiceIntegrationTest.draftIdFor(String)」。
    // 具体功能：「HearingOutcomeOrchestrationServiceIntegrationTest.draftIdFor(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「draftIdFor」）”组装或读取测试夹具数据，供本测试类的场景方法复用。
    // 上游调用：「HearingOutcomeOrchestrationServiceIntegrationTest.draftIdFor(String)」由本测试类中的 「HearingOutcomeOrchestrationServiceIntegrationTest.recoversCompletedHearingDraftIntoReviewGateIdempotently」、「HearingOutcomeOrchestrationServiceIntegrationTest.seedLegacyCompletedHearingWithoutRoute」 调用。
    // 下游影响：「HearingOutcomeOrchestrationServiceIntegrationTest.draftIdFor(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingOutcomeOrchestrationServiceIntegrationTest.draftIdFor(String)」守住「共享小法庭」的可执行规格，尤其防止 「DRAFT_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private String draftIdFor(String caseId) {
        return "DRAFT_" + caseId;
    }
}
