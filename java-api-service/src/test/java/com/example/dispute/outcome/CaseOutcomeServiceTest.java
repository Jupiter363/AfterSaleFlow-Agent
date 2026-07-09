package com.example.dispute.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.outcome.application.CaseOutcomeService;
import com.example.dispute.outcome.application.CaseOutcomeView;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.review.application.ReviewDecisionCommand;
import com.example.dispute.review.application.ReviewDecisionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CaseOutcomeServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private ApprovalRecordRepository approvalRepository;
    @Mock private AdjudicationDraftRepository draftRepository;
    @Mock private FlowConclusionRepository conclusionRepository;
    @Mock private ToolExecutorService executorService;
    @Mock private RemedyPlanRepository remedyPlanRepository;
    @Mock private ReviewTaskRepository reviewTaskRepository;
    @Mock private ReviewApplicationService reviewApplicationService;
    private CaseOutcomeService service;

    @BeforeEach
    void setUp() {
        service =
                new CaseOutcomeService(
                        caseRepository,
                        approvalRepository,
                        draftRepository,
                        conclusionRepository,
                        executorService,
                        remedyPlanRepository,
                        reviewTaskRepository,
                        reviewApplicationService,
                        new ObjectMapper());
    }

    @Test
    void projectsTheLatestHumanDecisionOverTheAdjudicationDraft() {
        FulfillmentCaseEntity dispute = dispute();
        ApprovalRecordEntity approval =
                ApprovalRecordEntity.record(
                        "APPROVAL_1",
                        dispute.getId(),
                        "TASK_1",
                        "PLAN_1",
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        ApprovalDecisionType.APPROVE,
                        "{}",
                        "{\"actions\":[{\"type\":\"REFUND\"}]}",
                        "审核员确认证据链完整。",
                        "hash-1");
        AdjudicationDraftEntity draft =
                AdjudicationDraftEntity.create(
                        "DRAFT_1",
                        dispute.getId(),
                        "HEARING_1",
                        2,
                        "[{\"fact\":\"物流记录显示已签收\",\"support_level\":\"SUPPORTED\"}]",
                        "[{\"assessment\":\"商家证据不足以证明用户本人签收\"}]",
                        "[{\"rule\":\"签收争议举证责任\"}]",
                        "[\"核验签收人身份\"]",
                        "支持用户退款请求",
                        new BigDecimal("0.9200"),
                        "商家举证不足。",
                        "adjudication-agent",
                        "READY",
                        "system");

        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(approvalRepository.findAllByCaseIdOrderByCreatedAtAsc(dispute.getId()))
                .thenReturn(List.of(approval));
        when(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(dispute.getId()))
                .thenReturn(Optional.of(draft));
        when(conclusionRepository.findByCaseId(dispute.getId()))
                .thenReturn(Optional.empty());
        when(executorService.actions(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER)))
                .thenReturn(List.of());

        CaseOutcomeView outcome =
                service.get(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(outcome.finalDecision().conclusion())
                .isEqualTo("支持用户退款请求");
        assertThat(outcome.finalDecision().explanation())
                .isEqualTo("商家举证不足。");
        assertThat(outcome.finalDecision().reviewReason())
                .isEqualTo("审核员确认证据链完整。");
        assertThat(outcome.finalDecision().humanConfirmed()).isTrue();
        assertThat(outcome.finalDecision().approvedPlan().at("/actions/0/type").asText())
                .isEqualTo("REFUND");
        assertThat(outcome.adjudicationDraft()).isNotNull();
        assertThat(outcome.adjudicationDraft().id()).isEqualTo("DRAFT_1");
        assertThat(outcome.adjudicationDraft().factFindings().get(0).path("fact").asText())
                .isEqualTo("物流记录显示已签收");
        assertThat(outcome.adjudicationDraft().evidenceAssessment().get(0).path("assessment").asText())
                .isEqualTo("商家证据不足以证明用户本人签收");
        assertThat(outcome.adjudicationDraft().policyApplication().get(0).path("rule").asText())
                .isEqualTo("签收争议举证责任");
        assertThat(outcome.adjudicationDraft().reviewerAttention().get(0).asText())
                .isEqualTo("核验签收人身份");
    }

    @Test
    void exposesLatestPendingRemedyPlanOnTheAdjudicationDraftForReviewerPrefill() {
        FulfillmentCaseEntity dispute = dispute();
        AdjudicationDraftEntity draft =
                AdjudicationDraftEntity.create(
                        "DRAFT_PREFILL",
                        dispute.getId(),
                        "HEARING_1",
                        3,
                        "[]",
                        "[]",
                        "[]",
                        "[]",
                        "SUPPORT_REFUND",
                        new BigDecimal("0.8100"),
                        "AI 法官已形成非最终裁决草案。",
                        "adjudication-agent",
                        "READY",
                        "system");
        RemedyPlanEntity plan =
                RemedyPlanEntity.pendingApproval(
                        "PLAN_PREFILL",
                        dispute.getId(),
                        draft.getId(),
                        1,
                        RouteType.SIMPLE_HEARING,
                        RiskLevel.MEDIUM,
                        "[{\"action_type\":\"REFUND\",\"amount\":188}]",
                        "[\"审核员确认后执行\"]",
                        "[\"通知用户和商家\"]",
                        "system");

        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(approvalRepository.findAllByCaseIdOrderByCreatedAtAsc(dispute.getId()))
                .thenReturn(List.of());
        when(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(dispute.getId()))
                .thenReturn(Optional.of(draft));
        when(conclusionRepository.findByCaseId(dispute.getId()))
                .thenReturn(Optional.empty());
        when(remedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(dispute.getId()))
                .thenReturn(Optional.of(plan));
        when(executorService.actions(
                        dispute.getId(),
                        new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER)))
                .thenReturn(List.of());

        CaseOutcomeView outcome =
                service.get(
                        dispute.getId(),
                        new AuthenticatedActor(
                                "reviewer-local",
                                ActorRole.PLATFORM_REVIEWER));

        assertThat(outcome.adjudicationDraft().approvedPlan().path("id").asText())
                .isEqualTo("PLAN_PREFILL");
        assertThat(outcome.adjudicationDraft().approvedPlan().path("version").asInt())
                .isEqualTo(1);
        assertThat(outcome.adjudicationDraft().approvedPlan().at("/actions/0/action_type").asText())
                .isEqualTo("REFUND");
        assertThat(outcome.adjudicationDraft().approvedPlan().at("/actions/0/amount").asInt())
                .isEqualTo(188);
    }

    @Test
    void rejectsAUserWhoDoesNotOwnTheDispute() {
        FulfillmentCaseEntity dispute = dispute();
        when(caseRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(dispute));

        assertThatThrownBy(
                        () ->
                                service.get(
                                        dispute.getId(),
                                        new AuthenticatedActor(
                                                "another-user",
                                                ActorRole.USER)))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(
                approvalRepository,
                draftRepository,
                conclusionRepository,
                executorService,
                remedyPlanRepository,
                reviewTaskRepository,
                reviewApplicationService);
    }

    @Test
    void reviewerConfirmsLatestDraftByCaseReviewTask() {
        ReviewTaskEntity task =
                ReviewTaskEntity.pending(
                        "REVIEW_1",
                        "CASE_outcome",
                        "PLAN_1",
                        "PACKET_1",
                        "HIGH",
                        "PLATFORM_REVIEWER",
                        OffsetDateTime.parse("2026-07-10T00:00:00Z"),
                        "system");
        AuthenticatedActor reviewer =
                new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER);
        ReviewDecisionView expected =
                new ReviewDecisionView(
                        "APPROVAL_1",
                        "REVIEW_1",
                        "CASE_outcome",
                        "APPROVE",
                        "APPROVED",
                        "APPROVED_FOR_EXECUTION",
                        true);
        when(reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc("CASE_outcome"))
                .thenReturn(Optional.of(task));
        when(reviewApplicationService.decide(
                        org.mockito.ArgumentMatchers.eq("REVIEW_1"),
                        org.mockito.ArgumentMatchers.any(ReviewDecisionCommand.class),
                        org.mockito.ArgumentMatchers.eq(reviewer)))
                .thenReturn(expected);

        ReviewDecisionView actual =
                service.confirmDraft(
                        "CASE_outcome",
                        "审核员确认 AI 裁决草案。",
                        "outcome-confirm-1",
                        reviewer);

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<ReviewDecisionCommand> command =
                ArgumentCaptor.forClass(ReviewDecisionCommand.class);
        verify(reviewApplicationService).decide(
                org.mockito.ArgumentMatchers.eq("REVIEW_1"),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(reviewer));
        assertThat(command.getValue().decision()).isEqualTo(ApprovalDecisionType.APPROVE);
        assertThat(command.getValue().reason()).contains("审核员确认");
        assertThat(command.getValue().idempotencyKey()).isEqualTo("outcome-confirm-1");
    }

    @Test
    void reviewerModifiesLatestDraftByCaseReviewTask() throws Exception {
        ReviewTaskEntity task =
                ReviewTaskEntity.pending(
                        "REVIEW_2",
                        "CASE_outcome",
                        "PLAN_2",
                        "PACKET_2",
                        "HIGH",
                        "PLATFORM_REVIEWER",
                        OffsetDateTime.parse("2026-07-10T00:00:00Z"),
                        "system");
        AuthenticatedActor reviewer =
                new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER);
        ObjectMapper mapper = new ObjectMapper();
        var modifiedPlan =
                mapper.readTree(
                        "{\"id\":\"PLAN_2\",\"actions\":[{\"action_type\":\"REFUND\",\"amount\":199}]}");
        ReviewDecisionView expected =
                new ReviewDecisionView(
                        "APPROVAL_2",
                        "REVIEW_2",
                        "CASE_outcome",
                        "MODIFY_AND_APPROVE",
                        "APPROVED",
                        "APPROVED_FOR_EXECUTION",
                        true);
        when(reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc("CASE_outcome"))
                .thenReturn(Optional.of(task));
        when(reviewApplicationService.decide(
                        org.mockito.ArgumentMatchers.eq("REVIEW_2"),
                        org.mockito.ArgumentMatchers.any(ReviewDecisionCommand.class),
                        org.mockito.ArgumentMatchers.eq(reviewer)))
                .thenReturn(expected);

        ReviewDecisionView actual =
                service.modifyDraft(
                        "CASE_outcome",
                        "审核员调整退款金额。",
                        modifiedPlan,
                        "outcome-modify-1",
                        reviewer);

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<ReviewDecisionCommand> command =
                ArgumentCaptor.forClass(ReviewDecisionCommand.class);
        verify(reviewApplicationService).decide(
                org.mockito.ArgumentMatchers.eq("REVIEW_2"),
                command.capture(),
                org.mockito.ArgumentMatchers.eq(reviewer));
        assertThat(command.getValue().decision())
                .isEqualTo(ApprovalDecisionType.MODIFY_AND_APPROVE);
        assertThat(command.getValue().approvedPlan()).isEqualTo(modifiedPlan);
    }

    private static FulfillmentCaseEntity dispute() {
        return FulfillmentCaseEntity.create(
                "CASE_outcome",
                "ORDER_1",
                "AFTER_SALE_1",
                "user-local",
                "merchant-local",
                "create-1",
                "DISPUTE",
                "签收未收到争议",
                "用户主张未收到包裹。",
                RiskLevel.MEDIUM,
                "user-local");
    }
}
