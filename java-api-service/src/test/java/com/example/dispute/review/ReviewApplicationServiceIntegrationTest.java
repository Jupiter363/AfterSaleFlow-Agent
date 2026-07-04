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

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({
    ReviewApplicationService.class,
    ReviewApplicationServiceIntegrationTest.JacksonConfig.class
})
@Testcontainers
class ReviewApplicationServiceIntegrationTest {
    @Container static final GenericContainer<?> POSTGRESQL=new GenericContainer<>(DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
            .withEnv("POSTGRES_DB","dispute_review").withEnv("POSTGRES_USER","dispute_test").withEnv("POSTGRES_PASSWORD","local_test_password").withExposedPorts(5432);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:postgresql://"+POSTGRESQL.getHost()+":"+POSTGRESQL.getMappedPort(5432)+"/dispute_review");
        r.add("spring.datasource.username",()->"dispute_test");r.add("spring.datasource.password",()->"local_test_password");
    }
    @TestConfiguration static class JacksonConfig{@Bean ObjectMapper objectMapper(){return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);}}
    @Autowired ReviewApplicationService service; @Autowired FulfillmentCaseRepository cases;
    @Autowired RemedyPlanRepository plans; @Autowired ReviewTaskRepository tasks;
    @Autowired ReviewPacketRepository packets; @Autowired ApprovalRecordRepository approvals;
    @Autowired ApprovalPolicyDecisionRepository policyDecisions;
    @MockitoBean AuditRecorder audit;
    @MockitoBean PostReviewOrchestrationService postReviewOrchestration;
    @MockitoBean CaseLifecycleNotificationService lifecycleNotifications;

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
    @Test void createsPacketAndOnlyReviewerCanModifyApproveWithDiff(){
        String taskId=service.createForWorkflow("CASE_review","REMEDY_review");
        verify(lifecycleNotifications)
                .reviewPending(
                        any(FulfillmentCaseEntity.class),
                        eq(taskId));
        var packet=service.packet(taskId,new AuthenticatedActor("cs-1",ActorRole.CUSTOMER_SERVICE));
        assertThat(packet.remedy().path("actions")).hasSize(1);
        assertThat(service.list(ReviewTaskStatus.PENDING,new AuthenticatedActor("reviewer-1",ActorRole.PLATFORM_REVIEWER))).hasSize(1);
        var approved=packet.remedy().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) approved)
                .put("reviewer_note","amount verified");
        assertThatThrownBy(()->service.decide(taskId,new ReviewDecisionCommand(ApprovalDecisionType.APPROVE,"approve",null,"cs-key"),new AuthenticatedActor("cs-1",ActorRole.CUSTOMER_SERVICE))).isInstanceOf(ForbiddenException.class);
        var result=service.decide(taskId,new ReviewDecisionCommand(ApprovalDecisionType.MODIFY_AND_APPROVE,"amount verified",approved,"review-key"),new AuthenticatedActor("reviewer-1",ActorRole.PLATFORM_REVIEWER));
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
                        new AuthenticatedActor("reviewer-1", ActorRole.PLATFORM_REVIEWER));
        assertThat(retry.approvalRecordId()).isEqualTo(result.approvalRecordId());
        assertThat(approvals.findAllByCaseIdOrderByCreatedAtAsc("CASE_review")).hasSize(1);
        assertThatThrownBy(() -> service.decide(
                        taskId,
                        new ReviewDecisionCommand(
                                ApprovalDecisionType.REQUEST_MORE_EVIDENCE,
                                "reuse key with different payload",
                                null,
                                "review-key"),
                        new AuthenticatedActor("reviewer-1", ActorRole.PLATFORM_REVIEWER)))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(postReviewOrchestration, times(2))
                .orchestrate(
                        eq(result.approvalRecordId()),
                        any(AuthenticatedActor.class),
                        eq("review-key"));
    }

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
                        "reviewer-1", ActorRole.PLATFORM_REVIEWER));

        verify(lifecycleNotifications)
                .supplementRequested(
                        any(FulfillmentCaseEntity.class),
                        eq("review-" + supplementTask));
    }

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
                        "reviewer-1", ActorRole.PLATFORM_REVIEWER));

        verify(lifecycleNotifications)
                .manualHandoff(
                        any(FulfillmentCaseEntity.class),
                        eq("ESCALATE_MANUAL"));
    }
}
