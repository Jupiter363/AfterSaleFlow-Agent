package com.example.dispute.outcome.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
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
    private final ObjectMapper objectMapper;

    public CaseOutcomeService(
            FulfillmentCaseRepository caseRepository,
            ApprovalRecordRepository approvalRepository,
            AdjudicationDraftRepository draftRepository,
            FlowConclusionRepository conclusionRepository,
            ToolExecutorService executorService,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.approvalRepository = approvalRepository;
        this.draftRepository = draftRepository;
        this.conclusionRepository = conclusionRepository;
        this.executorService = executorService;
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

        return new CaseOutcomeView(
                caseId,
                dispute.getTitle(),
                dispute.getCaseStatus(),
                dispute.getClosedAt(),
                finalDecision(dispute, approval, draft, flowConclusion),
                executorService.actions(caseId, actor));
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
}
