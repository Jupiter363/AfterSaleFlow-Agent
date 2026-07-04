package com.example.dispute.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.outcome.application.CaseOutcomeService;
import com.example.dispute.outcome.application.CaseOutcomeView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CaseOutcomeServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private ApprovalRecordRepository approvalRepository;
    @Mock private AdjudicationDraftRepository draftRepository;
    @Mock private FlowConclusionRepository conclusionRepository;
    @Mock private ToolExecutorService executorService;
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
                        "[]",
                        "[]",
                        "[]",
                        "[]",
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
                executorService);
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
