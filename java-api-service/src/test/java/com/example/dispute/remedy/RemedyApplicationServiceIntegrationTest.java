/*
 * 所属模块：确定性补救规划。
 * 文件职责：验证补救应用Integration，覆盖 「generatesIdempotentPlansForRegularRuleAndHearingSources」、「legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.entity.RouteDecisionEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.RouteDecisionRepository;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【确定性补救规划 / 自动化测试层】类型「RemedyApplicationServiceIntegrationTest」。
// 类型职责：集中验证补救应用Integration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「generatesIdempotentPlansForRegularRuleAndHearingSources」、「legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft」、「seedFlow」、「seedHearing」、「routedCase」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    RemedyApplicationService.class,
    RemedyApplicationServiceIntegrationTest.JacksonTestConfiguration.class
})
@Testcontainers
class RemedyApplicationServiceIntegrationTest {

    // 所属模块：【确定性补救规划 / 自动化测试层】类型「JacksonTestConfiguration」。
    // 类型职责：在 Spring 启动期装配Jackson所需 Bean 和基础设施参数；本类型显式提供 「objectMapper」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @TestConfiguration
    static class JacksonTestConfiguration {
        // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyApplicationServiceIntegrationTest.JacksonTestConfiguration.objectMapper()」。
        // 具体功能：「RemedyApplicationServiceIntegrationTest.JacksonTestConfiguration.objectMapper()」：作为测试辅助方法为“核对完整业务行为（场景方法「objectMapper」）”组装或读取「ObjectMapper」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「RemedyApplicationServiceIntegrationTest.JacksonTestConfiguration.objectMapper()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「RemedyApplicationServiceIntegrationTest.JacksonTestConfiguration.objectMapper()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「RemedyApplicationServiceIntegrationTest.JacksonTestConfiguration.objectMapper()」守住「确定性补救规划」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .findAndRegisterModules()
                    .setPropertyNamingStrategy(
                            PropertyNamingStrategies.SNAKE_CASE);
        }
    }

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_remedy")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyApplicationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「RemedyApplicationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「RemedyApplicationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「RemedyApplicationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RemedyApplicationServiceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「确定性补救规划」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_remedy");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private RemedyApplicationService service;
    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private RouteDecisionRepository routeRepository;
    @Autowired private FlowConclusionRepository conclusionRepository;
    @Autowired private HearingStateRepository hearingRepository;
    @Autowired private AdjudicationDraftRepository draftRepository;
    @Autowired private RemedyPlanRepository planRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @MockitoBean private AuditRecorder auditRecorder;

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources()」。
    // 具体功能：「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources()」：复现“核对完整业务行为（场景方法「generatesIdempotentPlansForRegularRuleAndHearingSources」）”场景：驱动 「service.generateForWorkflow」、「planRepository.count」、「caseRepository.findById」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_remedyregular」、「LOGISTICS_STATUS_READY」、「CASE_remedyrule」、「REFUND_OR_CANCEL_RECOMMENDED」。
    // 上游调用：「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources()」守住「确定性补救规划」的可执行规格，尤其防止 「CASE_remedyregular」、「LOGISTICS_STATUS_READY」、「CASE_remedyrule」、「REFUND_OR_CANCEL_RECOMMENDED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void generatesIdempotentPlansForRegularRuleAndHearingSources() {
        seedFlow(
                "CASE_remedyregular",
                RouteType.TRANSFERRED,
                RiskLevel.LOW,
                "LOGISTICS_STATUS_READY",
                "[\"QUERY_LOGISTICS\",\"PREPARE_STATUS_NOTICE\"]");
        seedFlow(
                "CASE_remedyrule",
                RouteType.SIMPLE_HEARING,
                RiskLevel.MEDIUM,
                "REFUND_OR_CANCEL_RECOMMENDED",
                "[\"CANCEL_ORDER\",\"REFUND\"]");
        seedHearing();

        String regularId =
                service.generateForWorkflow(
                        "CASE_remedyregular",
                        "CASEWORKFLOW_CASE_remedyregular");
        String replayedRegularId =
                service.generateForWorkflow(
                        "CASE_remedyregular",
                        "CASEWORKFLOW_CASE_remedyregular");
        String ruleId =
                service.generateForWorkflow(
                        "CASE_remedyrule",
                        "CASEWORKFLOW_CASE_remedyrule");
        String hearingId =
                service.generateForWorkflow(
                        "CASE_remedyhearing",
                        "CASEWORKFLOW_CASE_remedyhearing");

        assertThat(replayedRegularId).isEqualTo(regularId);
        assertThat(ruleId).isNotEqualTo(regularId);
        assertThat(hearingId).isNotEqualTo(ruleId);
        assertThat(planRepository.count()).isEqualTo(3);

        var regular =
                service.get(
                        "CASE_remedyregular",
                        new AuthenticatedActor("user-remedy", ActorRole.USER));
        assertThat(regular.actions())
                .extracting(action -> action.actionType())
                .containsExactly(
                        "QUERY_LOGISTICS", "PREPARE_STATUS_NOTICE");
        assertThat(regular.requiresHumanReview()).isTrue();

        var rule =
                service.get(
                        "CASE_remedyrule",
                        new AuthenticatedActor("reviewer-1", ActorRole.PLATFORM_REVIEWER));
        assertThat(rule.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(rule.actions())
                .allSatisfy(
                        action -> {
                            assertThat(action.requiresApproval()).isTrue();
                            assertThat(action.idempotencyKey()).contains("CASE_remedyrule");
                        });

        var hearing =
                service.get(
                        "CASE_remedyhearing",
                        new AuthenticatedActor("merchant-remedy", ActorRole.MERCHANT));
        assertThat(hearing.adjudicationDraftId()).isEqualTo("DRAFT_remedy");
        assertThat(hearing.actions()).singleElement()
                .satisfies(
                        action ->
                                assertThat(action.actionType())
                                        .isEqualTo("REFUND"));
        assertThat(caseRepository.findById("CASE_remedyhearing"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(CaseStatus.REMEDY_PLANNED));
    }

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyApplicationServiceIntegrationTest.legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft()」。
    // 具体功能：「RemedyApplicationServiceIntegrationTest.legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft()」：复现“核对完整业务行为（场景方法「legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft」）”场景：驱动 「caseRepository.saveAndFlush」、「hearingRepository.saveAndFlush」、「draftRepository.saveAndFlush」、「service.generateForWorkflow」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_legacyroomhearing」、「hearing-window-」、「temporal-worker」、「HEARING_legacyroom」。
    // 上游调用：「RemedyApplicationServiceIntegrationTest.legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RemedyApplicationServiceIntegrationTest.legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RemedyApplicationServiceIntegrationTest.legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft()」守住「确定性补救规划」的可执行规格，尤其防止 「CASE_legacyroomhearing」、「hearing-window-」、「temporal-worker」、「HEARING_legacyroom」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft() {
        String caseId = "CASE_legacyroomhearing";
        String workflowId = "hearing-window-" + caseId;
        FulfillmentCaseEntity disputeCase =
                routedCase(
                        caseId,
                        RouteType.FULL_HEARING,
                        RiskLevel.HIGH);
        disputeCase.startHearing(workflowId, "temporal-worker");
        caseRepository.saveAndFlush(disputeCase);
        HearingStateEntity hearing =
                HearingStateEntity.start(
                        "HEARING_legacyroom",
                        caseId,
                        workflowId,
                        "temporal-worker");
        hearing.complete(true, "temporal-worker");
        hearingRepository.saveAndFlush(hearing);
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        "DRAFT_legacyroom",
                        caseId,
                        hearing.getId(),
                        1,
                        "[]",
                        "[]",
                        "[]",
                        "[\"verify fallback draft\"]",
                        "MANUAL_REVIEW_REQUIRED",
                        new BigDecimal("0.1000"),
                        "Fallback non-final draft for reviewer confirmation.",
                        "python-agent-service/fallback",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));
        entityManager.flush();
        jdbcTemplate.update(
                "update fulfillment_dispute_case set hearing_route = null where id = ?",
                caseId);
        entityManager.clear();

        String planId = service.generateForWorkflow(caseId, workflowId);

        assertThat(planRepository.findById(planId))
                .hasValueSatisfying(
                        plan -> {
                            assertThat(plan.getSourceRoute())
                                    .isEqualTo(RouteType.FULL_HEARING);
                            assertThat(plan.getAdjudicationDraftId())
                                    .isEqualTo("DRAFT_legacyroom");
                        });
        assertThat(caseRepository.findById(caseId))
                .hasValueSatisfying(
                        saved -> {
                            assertThat(saved.getRouteType())
                                    .isEqualTo(RouteType.FULL_HEARING);
                            assertThat(saved.getCaseStatus())
                                    .isEqualTo(CaseStatus.REMEDY_PLANNED);
                        });
    }

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyApplicationServiceIntegrationTest.seedFlow(String,RouteType,RiskLevel,String,String)」。
    // 具体功能：「RemedyApplicationServiceIntegrationTest.seedFlow(String,RouteType,RiskLevel,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「seedFlow」）”组装或读取「caseRepository.saveAndFlush」、「routeRepository.saveAndFlush」、「conclusionRepository.saveAndFlush」、「RouteDecisionEntity.record」，供本测试类的场景方法复用。
    // 上游调用：「RemedyApplicationServiceIntegrationTest.seedFlow(String,RouteType,RiskLevel,String,String)」由本测试类中的 「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources」 调用。
    // 下游影响：「RemedyApplicationServiceIntegrationTest.seedFlow(String,RouteType,RiskLevel,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RemedyApplicationServiceIntegrationTest.seedFlow(String,RouteType,RiskLevel,String,String)」守住「确定性补救规划」的可执行规格，尤其防止 「ROUTE_」、「IDEMPOTENCY_ROUTE_」、「TEST_ROUTE」、「{}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seedFlow(
            String caseId,
            RouteType routeType,
            RiskLevel riskLevel,
            String conclusionCode,
            String actionsJson) {
        FulfillmentCaseEntity disputeCase =
                routedCase(caseId, routeType, riskLevel);
        caseRepository.saveAndFlush(disputeCase);
        RouteDecisionEntity route =
                routeRepository.saveAndFlush(
                        RouteDecisionEntity.record(
                                "ROUTE_" + caseId,
                                caseId,
                                "IDEMPOTENCY_ROUTE_" + caseId,
                                routeType,
                                "TEST_ROUTE",
                                "Test route for remedy planning.",
                                false,
                                1,
                                null,
                                "{}",
                                "system"));
        conclusionRepository.saveAndFlush(
                FlowConclusionEntity.readyForRemedyPlanning(
                        "CONCLUSION_" + caseId,
                        caseId,
                        route.getId(),
                        routeType == RouteType.TRANSFERRED
                                ? "REGULAR_FLOW"
                                : "RULE_FLOW",
                        conclusionCode,
                        "Upstream conclusion must be preserved.",
                        actionsJson,
                        null,
                        null,
                        riskLevel,
                        "system"));
    }

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyApplicationServiceIntegrationTest.seedHearing()」。
    // 具体功能：「RemedyApplicationServiceIntegrationTest.seedHearing()」：作为测试辅助方法为“核对完整业务行为（场景方法「seedHearing」）”组装或读取「BigDecimal」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「RemedyApplicationServiceIntegrationTest.seedHearing()」由本测试类中的 「RemedyApplicationServiceIntegrationTest.generatesIdempotentPlansForRegularRuleAndHearingSources」 调用。
    // 下游影响：「RemedyApplicationServiceIntegrationTest.seedHearing()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RemedyApplicationServiceIntegrationTest.seedHearing()」守住「确定性补救规划」的可执行规格，尤其防止 「CASE_remedyhearing」、「CASEWORKFLOW_CASE_remedyhearing」、「temporal-worker」、「HEARING_remedy」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private void seedHearing() {
        FulfillmentCaseEntity disputeCase =
                routedCase(
                        "CASE_remedyhearing",
                        RouteType.FULL_HEARING,
                        RiskLevel.HIGH);
        disputeCase.startHearing(
                "CASEWORKFLOW_CASE_remedyhearing", "temporal-worker");
        caseRepository.saveAndFlush(disputeCase);
        HearingStateEntity hearing =
                HearingStateEntity.start(
                        "HEARING_remedy",
                        disputeCase.getId(),
                        "CASEWORKFLOW_CASE_remedyhearing",
                        "temporal-worker");
        hearing.complete(false, "temporal-worker");
        hearingRepository.saveAndFlush(hearing);
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        "DRAFT_remedy",
                        disputeCase.getId(),
                        hearing.getId(),
                        1,
                        "[]",
                        "[]",
                        "[]",
                        "[\"verify amount\"]",
                        "REFUND_AFTER_PLATFORM_REVIEW",
                        new BigDecimal("0.8000"),
                        "Non-final draft recommendation.",
                        "python-agent-service/test-model",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));
    }

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyApplicationServiceIntegrationTest.routedCase(String,RouteType,RiskLevel)」。
    // 具体功能：「RemedyApplicationServiceIntegrationTest.routedCase(String,RouteType,RiskLevel)」：作为测试辅助方法为“核对完整业务行为（场景方法「routedCase」）”组装或读取「FulfillmentCaseEntity.create」、「disputeCase.completeIntake」、「disputeCase.markDossierBuilt」、「disputeCase.applyRoute」，供本测试类的场景方法复用。
    // 上游调用：「RemedyApplicationServiceIntegrationTest.routedCase(String,RouteType,RiskLevel)」由本测试类中的 「RemedyApplicationServiceIntegrationTest.legacyRoomHearingWithoutRouteStillPlansFromCompletedDraft」、「RemedyApplicationServiceIntegrationTest.seedFlow」、「RemedyApplicationServiceIntegrationTest.seedHearing」 调用。
    // 下游影响：「RemedyApplicationServiceIntegrationTest.routedCase(String,RouteType,RiskLevel)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RemedyApplicationServiceIntegrationTest.routedCase(String,RouteType,RiskLevel)」守住「确定性补救规划」的可执行规格，尤其防止 「ORDER_」、「user-remedy」、「merchant-remedy」、「CREATE_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private FulfillmentCaseEntity routedCase(
            String caseId, RouteType routeType, RiskLevel riskLevel) {
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + caseId,
                        null,
                        "user-remedy",
                        "merchant-remedy",
                        "CREATE_" + caseId,
                        "REMEDY_TEST",
                        "remedy planning",
                        "upstream conclusion is ready for remedy planning",
                        riskLevel,
                        "user-remedy");
        disputeCase.completeIntake(
                routeType == RouteType.FULL_HEARING
                        ? "FULFILLMENT_CONFLICT"
                        : null,
                CaseStatus.INTAKE_COMPLETED,
                riskLevel,
                "{}",
                "user-remedy");
        disputeCase.markDossierBuilt("user-remedy");
        disputeCase.applyRoute(routeType, "user-remedy");
        return disputeCase;
    }
}
