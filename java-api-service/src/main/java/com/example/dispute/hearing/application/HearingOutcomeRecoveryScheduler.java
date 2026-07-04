package com.example.dispute.hearing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HearingOutcomeRecoveryScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingOutcomeRecoveryScheduler.class);
    private final HearingOutcomeOrchestrationService service;

    public HearingOutcomeRecoveryScheduler(
            HearingOutcomeOrchestrationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dispute.post-hearing-recovery-scan-delay:PT30S}")
    public void recover() {
        try {
            int recovered = service.recoverCompletedHearingsWithoutReview(20);
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
