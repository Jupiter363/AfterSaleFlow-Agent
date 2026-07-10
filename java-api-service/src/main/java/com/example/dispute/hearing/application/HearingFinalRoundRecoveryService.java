package com.example.dispute.hearing.application;

import com.example.dispute.config.DisputeProperties;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class HearingFinalRoundRecoveryService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingFinalRoundRecoveryService.class);
    private static final List<HearingRoundStatus> SEALED_STATUSES =
            List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED);

    private final HearingRoundRepository roundRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final HearingCourtOrchestrator courtOrchestrator;
    private final HearingWorkflowCoordinator workflowCoordinator;
    private final DisputeProperties disputeProperties;

    public HearingFinalRoundRecoveryService(
            HearingRoundRepository roundRepository,
            AdjudicationDraftRepository draftRepository,
            HearingCourtOrchestrator courtOrchestrator,
            HearingWorkflowCoordinator workflowCoordinator,
            DisputeProperties disputeProperties) {
        this.roundRepository = roundRepository;
        this.draftRepository = draftRepository;
        this.courtOrchestrator = courtOrchestrator;
        this.workflowCoordinator = workflowCoordinator;
        this.disputeProperties = disputeProperties;
    }

    public int recoverFinalRoundsWithoutDraft(int limit) {
        if (limit <= 0) {
            return 0;
        }
        int finalRoundNo = disputeProperties.maxHearingRounds();
        int recovered = 0;
        int pageNo = 0;
        while (recovered < limit) {
            var candidates =
                    roundRepository.findFinalRoundsWithoutDraft(
                            finalRoundNo,
                            finalRoundNo + 1,
                            SEALED_STATUSES,
                            PageRequest.of(pageNo, limit));
            if (candidates.isEmpty()) {
                break;
            }
            for (var round : candidates) {
                String caseId = round.getCaseId();
                if (draftRepository
                        .findByCaseIdAndDraftVersion(caseId, finalRoundNo + 1)
                        .isPresent()) {
                    continue;
                }
                try {
                    courtOrchestrator.afterRoundClosed(
                            caseId,
                            finalRoundNo,
                            true,
                            "TRACE_HEARING_FINAL_RECOVERY_" + finalRoundNo);
                    if (!courtOrchestrator.hasCompleteFormalJuryReport(
                            caseId, finalRoundNo)) {
                        LOGGER.warn(
                            "Skipping final hearing signal because formal jury report is missing: case_id={}, round_no={}",
                            caseId,
                                finalRoundNo);
                        continue;
                    }
                    if (workflowCoordinator.roundCompletedNow(caseId, finalRoundNo, false)) {
                        recovered++;
                        if (recovered >= limit) {
                            break;
                        }
                    }
                } catch (RuntimeException failure) {
                    LOGGER.warn(
                            "Failed to recover final hearing convergence: case_id={}, round_no={}",
                            caseId,
                            finalRoundNo,
                            failure);
                }
            }
            if (candidates.size() < limit) {
                break;
            }
            pageNo++;
        }
        return recovered;
    }
}
