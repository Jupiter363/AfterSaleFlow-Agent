package com.example.dispute.hearing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HearingOutcomeRecoveryScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingOutcomeRecoveryScheduler.class);
    private final HearingFinalRoundRecoveryService finalRoundRecoveryService;
    private final HearingOutcomeOrchestrationService outcomeService;

    public HearingOutcomeRecoveryScheduler(
            HearingFinalRoundRecoveryService finalRoundRecoveryService,
            HearingOutcomeOrchestrationService outcomeService) {
        this.finalRoundRecoveryService = finalRoundRecoveryService;
        this.outcomeService = outcomeService;
    }

    @Scheduled(fixedDelayString = "${dispute.post-hearing-recovery-scan-delay:PT30S}")
    public void recover() {
        try {
            int finalRounds = finalRoundRecoveryService.recoverFinalRoundsWithoutDraft(20);
            if (finalRounds > 0) {
                LOGGER.info(
                        "Recovered sealed final hearing rounds into Temporal convergence: count={}",
                        finalRounds);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to recover sealed final hearing rounds", exception);
        }
        try {
            int recovered = outcomeService.recoverCompletedHearingsWithoutReview(20);
            if (recovered > 0) {
                LOGGER.info(
                        "Recovered completed hearing outcomes into review gate: count={}",
                        recovered);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to recover completed hearing outcomes", exception);
        }
    }
}
