package com.example.dispute.outcome.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.PlatformReviewerAuthorization;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.review.application.ReviewDecisionCommand;
import com.example.dispute.review.application.ReviewDecisionView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseOutcomeService {

    private final FulfillmentCaseRepository caseRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final FlowConclusionRepository conclusionRepository;
    private final ToolExecutorService executorService;
    private final RemedyPlanRepository remedyPlanRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final ReviewApplicationService reviewApplicationService;
    private final ObjectMapper objectMapper;

    public CaseOutcomeService(
            FulfillmentCaseRepository caseRepository,
            ApprovalRecordRepository approvalRepository,
            AdjudicationDraftRepository draftRepository,
            FlowConclusionRepository conclusionRepository,
            ToolExecutorService executorService,
            RemedyPlanRepository remedyPlanRepository,
            ReviewTaskRepository reviewTaskRepository,
            ReviewApplicationService reviewApplicationService,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.approvalRepository = approvalRepository;
        this.draftRepository = draftRepository;
        this.conclusionRepository = conclusionRepository;
        this.executorService = executorService;
        this.remedyPlanRepository = remedyPlanRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.reviewApplicationService = reviewApplicationService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CaseOutcomeView get(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        assertCanRead(dispute, actor);
        ApprovalRecordEntity approval =
                latest(approvalRepository.findAllByCaseIdOrderByCreatedAtAsc(caseId));
        AdjudicationDraftEntity draft =
                draftRepository
                        .findFirstByCaseIdOrderByDraftVersionDesc(caseId)
                        .orElse(null);
        FlowConclusionEntity flowConclusion =
                conclusionRepository.findByCaseId(caseId).orElse(null);
        RemedyPlanEntity remedyPlan =
                remedyPlanRepository
                        .findFirstByCaseIdOrderByPlanVersionDesc(caseId)
                        .orElse(null);

        return new CaseOutcomeView(
                caseId,
                dispute.getTitle(),
                dispute.getCaseStatus(),
                dispute.getClosedAt(),
                finalDecision(dispute, approval, draft, flowConclusion),
                adjudicationDraft(draft, remedyPlan),
                executorService.actions(caseId, actor));
    }

    public ReviewDecisionView confirmDraft(
            String caseId,
            String reason,
            String idempotencyKey,
            AuthenticatedActor actor) {
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        String taskId = latestReviewTaskId(caseId);
        return reviewApplicationService.decide(
                taskId,
                new ReviewDecisionCommand(
                        ApprovalDecisionType.APPROVE,
                        reason,
                        null,
                        idempotencyKey),
                actor);
    }

    public ReviewDecisionView modifyDraft(
            String caseId,
            String reason,
            JsonNode approvedPlan,
            String idempotencyKey,
            AuthenticatedActor actor) {
        PlatformReviewerAuthorization.requireDecisionAccess(actor);
        String taskId = latestReviewTaskId(caseId);
        return reviewApplicationService.decide(
                taskId,
                new ReviewDecisionCommand(
                        ApprovalDecisionType.MODIFY_AND_APPROVE,
                        reason,
                        approvedPlan,
                        idempotencyKey),
                actor);
    }

    private AdjudicationDraftView adjudicationDraft(
            AdjudicationDraftEntity draft, RemedyPlanEntity remedyPlan) {
        if (draft == null) {
            return null;
        }
        return new AdjudicationDraftView(
                draft.getId(),
                draft.getDraftVersion(),
                draft.getRecommendedDecision(),
                draft.getConfidence(),
                draft.getDraftText(),
                draft.getDraftStatus(),
                json(draft.getFactFindingsJson()),
                json(draft.getEvidenceAssessmentJson()),
                json(draft.getPolicyApplicationJson()),
                json(draft.getReviewerAttentionJson()),
                approvedPlan(remedyPlan));
    }

    private JsonNode approvedPlan(RemedyPlanEntity plan) {
        if (plan == null) {
            return null;
        }
        return objectMapper.valueToTree(
                Map.of(
                        "id",
                        plan.getId(),
                        "version",
                        plan.getPlanVersion(),
                        "actions",
                        json(plan.getActionsJson()),
                        "preconditions",
                        json(plan.getPreconditionsJson()),
                        "notifications",
                        json(plan.getNotificationPlanJson())));
    }

    private FinalDecisionView finalDecision(
            FulfillmentCaseEntity dispute,
            ApprovalRecordEntity approval,
            AdjudicationDraftEntity draft,
            FlowConclusionEntity flowConclusion) {
        String conclusion =
                draft != null
                        ? draft.getRecommendedDecision()
                        : flowConclusion != null
                                ? flowConclusion.getConclusionCode()
                                : "历史结案记录";
        if (approval != null
                && approval.getDecisionType() == ApprovalDecisionType.REJECT) {
            conclusion = "平台终审驳回裁决草案";
        }
        String explanation =
                draft != null
                        ? draft.getDraftText()
                        : flowConclusion != null
                                ? flowConclusion.getSummary()
                                : dispute.getDescription();
        return new FinalDecisionView(
                conclusion,
                explanation,
                approval == null ? null : approval.getDecisionReason(),
                approval != null
                        ? "HUMAN_REVIEW"
                        : dispute.getSourceType().name(),
                approval != null,
                approval == null
                        ? null
                        : json(approval.getApprovedPlanJson()));
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "invalid approved plan JSON", exception);
        }
    }

    private static ApprovalRecordEntity latest(
            List<ApprovalRecordEntity> records) {
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    private static void assertCanRead(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT ->
                            actor.actorId().equals(dispute.getMerchantId());
                    case CUSTOMER_SERVICE,
                            PLATFORM_REVIEWER,
                            ADMIN,
                            SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot view case outcome");
        }
    }

    private String latestReviewTaskId(String caseId) {
        return reviewTaskRepository
                .findFirstByCaseIdOrderByCreatedAtDesc(caseId)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        ErrorCode.CASE_NOT_FOUND,
                                        "review task not found",
                                        Map.of("case_id", caseId)))
                .getId();
    }
}
