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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    ToolExecutorService.class,
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

    @TestConfiguration
    static class JacksonConfig {
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
    @MockitoBean private AuditRecorder auditRecorder;
    @MockitoBean private ActionExecutionLock executionLock;

    @BeforeEach
    void configureExecutionLock() {
        when(executionLock.acquire(anyString())).thenReturn("test-lock-owner");
    }

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
                        });
        assertThat(cases.findById("CASE_success"))
                .hasValueSatisfying(
                        disputeCase ->
                                assertThat(disputeCase.getCaseStatus())
                                        .isEqualTo(CaseStatus.EXECUTING));
    }

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

    private void seed(String suffix, boolean approved, boolean simulateFailure) {
        seed(suffix, approved, simulateFailure, false, "REFUND");
    }

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
                    ApprovalRecordEntity.record(
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
                            "HASH_" + suffix));
        }
    }
}
