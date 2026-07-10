package com.example.dispute.hearing.application;

import com.example.dispute.config.DisputeProperties;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<RecoveryCursor> cursor =
            new AtomicReference<>(RecoveryCursor.start());

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
        int scanned = 0;
        RecoveryCursor current = cursor.get();
        boolean startedAtBeginning = current.isStart();
        boolean wrapped = false;
        Set<String> seenRoundIds = new HashSet<>();
        while (scanned < limit) {
            int pageSize = limit - scanned;
            var candidates =
                    roundRepository.findFinalRoundsWithoutDraftAfter(
                            finalRoundNo,
                            finalRoundNo + 1,
                            SEALED_STATUSES,
                            current.closedAt(),
                            current.roundId(),
                            PageRequest.of(0, pageSize));
            if (candidates.isEmpty()) {
                if (!current.isStart() && !wrapped) {
                    current = RecoveryCursor.start();
                    cursor.set(current);
                    wrapped = true;
                    continue;
                }
                break;
            }
            for (var round : candidates) {
                current = RecoveryCursor.after(round);
                cursor.set(current);
                scanned++;
                if (!seenRoundIds.add(round.getId())) {
                    if (scanned >= limit) {
                        break;
                    }
                    continue;
                }
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
                    }
                } catch (RuntimeException failure) {
                    LOGGER.warn(
                            "Failed to recover final hearing convergence: case_id={}, round_no={}",
                            caseId,
                            finalRoundNo,
                            failure);
                }
                if (scanned >= limit) {
                    break;
                }
            }
            if (scanned >= limit) {
                break;
            }
            if (candidates.size() < pageSize) {
                if (!startedAtBeginning && !wrapped) {
                    current = RecoveryCursor.start();
                    cursor.set(current);
                    wrapped = true;
                    continue;
                }
                break;
            }
        }
        return recovered;
    }

    private record RecoveryCursor(Instant closedAt, String roundId) {

        private static RecoveryCursor start() {
            return new RecoveryCursor(null, "");
        }

        private static RecoveryCursor after(
                com.example.dispute.hearing.infrastructure.persistence.entity
                                .HearingRoundEntity
                        round) {
            if (round.getClosedAt() == null) {
                throw new IllegalStateException(
                        "sealed final round must have closedAt for keyset recovery");
            }
            return new RecoveryCursor(round.getClosedAt(), round.getId());
        }

        private boolean isStart() {
            return closedAt == null;
        }
    }
}
