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

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    RemedyApplicationService.class,
    RemedyApplicationServiceIntegrationTest.JacksonTestConfiguration.class
})
@Testcontainers
class RemedyApplicationServiceIntegrationTest {

    @TestConfiguration
    static class JacksonTestConfiguration {
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
