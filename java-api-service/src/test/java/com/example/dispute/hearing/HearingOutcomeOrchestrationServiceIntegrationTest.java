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

    @TestConfiguration
    static class JacksonConfig {
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

    private String hearingIdFor(String caseId) {
        return "HEARING_" + caseId;
    }

    private String draftIdFor(String caseId) {
        return "DRAFT_" + caseId;
    }
}
