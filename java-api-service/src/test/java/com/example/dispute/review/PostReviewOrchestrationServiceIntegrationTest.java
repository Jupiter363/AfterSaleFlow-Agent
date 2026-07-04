package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.evaluation.application.EvaluationAgentClient;
import com.example.dispute.evaluation.application.EvaluationAgentResult;
import com.example.dispute.executor.application.ActionExecutionLock;
import com.example.dispute.executor.application.ToolExecutorService;
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
import com.example.dispute.review.application.PostReviewOrchestrationService;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.review.domain.ReviewPacketVersions;
import com.example.dispute.tool.application.SimulatedExecutionTool;
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

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    PostReviewOrchestrationService.class,
    ToolExecutorService.class,
    CaseClosureService.class,
    SimulatedExecutionTool.class,
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

    @TestConfiguration
    static class JacksonConfig {
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
    @MockitoBean ActionExecutionLock executionLock;
    @MockitoBean EvaluationAgentClient evaluationAgent;
    @MockitoBean AuditRecorder auditRecorder;
    @MockitoBean CaseLifecycleNotificationService lifecycleNotifications;

    @BeforeEach
    void resetDataAndMocks() {
        actions.deleteAllInBatch();
        evaluations.deleteAllInBatch();
        approvals.deleteAllInBatch();
        reviewTasks.deleteAllInBatch();
        packets.deleteAllInBatch();
        plans.deleteAllInBatch();
        cases.deleteAllInBatch();
        when(executionLock.acquire(anyString())).thenReturn("test-lock-owner");
        when(evaluationAgent.analyze(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            JsonNode snapshot = invocation.getArgument(0);
                            var report =
                                    (com.fasterxml.jackson.databind.node.ObjectNode)
                                            objectMapper.readTree(
                                                    """
                                                    {
                                                      "evaluation_status":"COMPLETED",
                                                      "metric_scores":{
                                                        "draft_approval_rate":1.0,
                                                        "reviewer_modification_rate":0.0,
                                                        "overall_quality_score":0.9
                                                      },
                                                      "findings":[],
                                                      "automatic_changes_applied":false,
                                                      "online_case_mutated":false
                                                    }
                                                    """);
                            report.put("case_id", snapshot.path("case_id").asText());
                            return new EvaluationAgentResult(
                                    report, "evaluation-model", "evaluation-v1", 8, 21);
                        });
    }

    @Test
    void approvedReviewExecutesApprovedActionsAndClosesCase() {
        seed("approved", ApprovalDecisionType.APPROVE);

        var result =
                service.orchestrate(
                        "APPROVAL_approved", REVIEWER, "post-review-approved");

        assertThat(result.status()).isEqualTo("CLOSED");
        assertThat(result.executionAttempted()).isTrue();
        assertThat(result.closureAttempted()).isTrue();
        assertThat(cases.findById("CASE_approved"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(CaseStatus.CLOSED));
        assertThat(actions.findAllByCaseIdOrderByCreatedAtAsc("CASE_approved"))
                .hasSize(2)
                .allSatisfy(
                        action ->
                                assertThat(action.getExecutionStatus())
                                        .isEqualTo(ExecutionStatus.SUCCEEDED));
        assertThat(evaluations.findAll()).singleElement()
                .satisfies(
                        trace ->
                                assertThat(trace.getEvaluationStatus())
                                        .isEqualTo("COMPLETED"));
    }

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

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
