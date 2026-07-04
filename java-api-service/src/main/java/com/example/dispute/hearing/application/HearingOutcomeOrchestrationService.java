package com.example.dispute.hearing.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.domain.model.HearingStatus;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HearingOutcomeOrchestrationService {

    private static final Logger log =
            LoggerFactory.getLogger(HearingOutcomeOrchestrationService.class);

    private final HearingStateRepository hearingRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final RemedyPlanRepository remedyRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final RemedyApplicationService remedyService;
    private final ReviewApplicationService reviewService;
    private final TransactionTemplate recoveryTransaction;

    public HearingOutcomeOrchestrationService(
            HearingStateRepository hearingRepository,
            AdjudicationDraftRepository draftRepository,
            RemedyPlanRepository remedyRepository,
            ReviewTaskRepository reviewTaskRepository,
            RemedyApplicationService remedyService,
            ReviewApplicationService reviewService,
            PlatformTransactionManager transactionManager) {
        this.hearingRepository = hearingRepository;
        this.draftRepository = draftRepository;
        this.remedyRepository = remedyRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.remedyService = remedyService;
        this.reviewService = reviewService;
        this.recoveryTransaction = new TransactionTemplate(transactionManager);
        this.recoveryTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public HearingOutcomeOrchestrationResult orchestrate(
            String caseId, String actorId) {
        HearingStateEntity hearing =
                hearingRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "hearing state not found",
                                                Map.of("case_id", caseId)));
        return orchestrate(hearing);
    }

    public int recoverCompletedHearingsWithoutReview(int limit) {
        if (limit <= 0) {
            return 0;
        }
        int recovered = 0;
        for (String caseId :
                hearingRepository.findAllByHearingStatusOrderByCompletedAtAsc(
                                HearingStatus.COMPLETED)
                        .stream()
                        .map(HearingStateEntity::getCaseId)
                        .toList()) {
            if (recovered >= limit) {
                break;
            }
            try {
                Boolean created =
                        recoveryTransaction.execute(
                                status -> recoverSingleCompletedHearing(caseId));
                if (Boolean.TRUE.equals(created)) {
                    recovered++;
                }
            } catch (BusinessException ex) {
                log.warn(
                        "Skipping completed hearing recovery for case {} because {}",
                        caseId,
                        ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn(
                        "Skipping completed hearing recovery for case {} because orchestration failed",
                        caseId,
                        ex);
            }
        }
        return recovered;
    }

    private boolean recoverSingleCompletedHearing(String caseId) {
        HearingStateEntity hearing =
                hearingRepository
                        .findByCaseId(caseId)
                        .orElse(null);
        if (hearing == null || hearing.getHearingStatus() != HearingStatus.COMPLETED) {
            return false;
        }
        if (reviewTaskRepository
                .findFirstByCaseIdOrderByCreatedAtDesc(hearing.getCaseId())
                .isPresent()) {
            return false;
        }
        if (draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(caseId).isEmpty()) {
            log.warn(
                    "Skipping completed hearing recovery for case {} because adjudication draft is missing",
                    caseId);
            return false;
        }
        HearingOutcomeOrchestrationResult result = orchestrate(hearing);
        return result.createdRemedy() || result.createdReviewTask();
    }

    private HearingOutcomeOrchestrationResult orchestrate(
            HearingStateEntity hearing) {
        if (hearing.getHearingStatus() != HearingStatus.COMPLETED) {
            return new HearingOutcomeOrchestrationResult(
                    hearing.getCaseId(), null, null, false, false, "SKIPPED_NOT_COMPLETED");
        }
        draftRepository
                .findFirstByCaseIdOrderByDraftVersionDesc(hearing.getCaseId())
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.CASE_STATUS_INVALID,
                                        "adjudication draft is required before review gate",
                                        Map.of("case_id", hearing.getCaseId())));

        var existingPlan =
                remedyRepository.findFirstByCaseIdOrderByPlanVersionDesc(
                        hearing.getCaseId());
        boolean createdRemedy = existingPlan.isEmpty();
        String remedyPlanId =
                existingPlan
                        .map(plan -> plan.getId())
                        .orElseGet(
                                () ->
                                        remedyService.generateForWorkflow(
                                                hearing.getCaseId(),
                                                hearing.getWorkflowId()));

        var existingTask =
                reviewTaskRepository.findFirstByCaseIdOrderByCreatedAtDesc(
                        hearing.getCaseId());
        boolean createdReviewTask = existingTask.isEmpty();
        String reviewTaskId =
                existingTask
                        .map(task -> task.getId())
                        .orElseGet(
                                () ->
                                        reviewService.createForWorkflow(
                                                hearing.getCaseId(), remedyPlanId));

        return new HearingOutcomeOrchestrationResult(
                hearing.getCaseId(),
                remedyPlanId,
                reviewTaskId,
                createdRemedy,
                createdReviewTask,
                "REVIEW_GATE_READY");
    }
}
