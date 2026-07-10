package com.example.dispute.hearing.application;

import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adopts the final draft produced by the Temporal C6 activity.
 *
 * <p>This service deliberately has no agent client and no draft creation path. Final adjudication
 * draft generation belongs exclusively to the Temporal final-convergence activity.
 */
@Service
public class HearingFinalDraftService {

    static final String FINAL_DRAFT_NODE = "C6_DRAFT_GENERATION";

    private final HearingStateRepository stateRepository;
    private final HearingRoundRepository roundRepository;
    private final AdjudicationDraftRepository draftRepository;

    public HearingFinalDraftService(
            HearingStateRepository stateRepository,
            HearingRoundRepository roundRepository,
            AdjudicationDraftRepository draftRepository) {
        this.stateRepository = stateRepository;
        this.roundRepository = roundRepository;
        this.draftRepository = draftRepository;
    }

    @Transactional
    public String adoptExistingDraftForFinalSealedRound(
            String caseId, int finalRoundNo, int maxStatementRounds, String actorId) {
        int draftVersion = finalRoundNo + 1;
        AdjudicationDraftEntity existing =
                draftRepository
                        .findByCaseIdAndDraftVersion(caseId, draftVersion)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "final adjudication draft is not available"));
        HearingRoundEntity finalRound =
                roundRepository
                        .findByCaseIdAndRoundNo(caseId, finalRoundNo)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "final hearing round not found"));
        assertFinalRoundSealed(finalRound, finalRoundNo, maxStatementRounds);
        completeStateIfNeeded(caseId, finalRoundNo, existing, actorId);
        return existing.getId();
    }

    private void completeStateIfNeeded(
            String caseId, int finalRoundNo, AdjudicationDraftEntity draft, String actorId) {
        stateRepository
                .findByCaseId(caseId)
                .ifPresent(
                        state -> {
                            if (state.getCompletedAt() == null) {
                                BigDecimal confidence =
                                        draft.getConfidence() == null
                                                ? BigDecimal.valueOf(0.25)
                                                : draft.getConfidence();
                                state.applyAnalysis(
                                        finalRoundNo,
                                        FINAL_DRAFT_NODE,
                                        confidence,
                                        false,
                                        true,
                                        "{}",
                                        "[]",
                                        "[]",
                                        OffsetDateTime.now(ZoneOffset.UTC),
                                        actorId);
                                state.complete(true, actorId);
                                stateRepository.save(state);
                            }
                        });
    }

    private void assertFinalRoundSealed(
            HearingRoundEntity finalRound, int finalRoundNo, int maxStatementRounds) {
        boolean terminalStatus =
                finalRound.getRoundStatus() == HearingRoundStatus.COMPLETED
                        || finalRound.getRoundStatus() == HearingRoundStatus.FORCED_CLOSED;
        if (!terminalStatus
                || finalRound.getClosedAt() == null
                || finalRoundNo < maxStatementRounds) {
            throw new IllegalStateException("final hearing round is not sealed");
        }
    }
}
