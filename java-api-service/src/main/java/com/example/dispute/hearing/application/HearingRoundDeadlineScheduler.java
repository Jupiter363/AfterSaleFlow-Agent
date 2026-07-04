package com.example.dispute.hearing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HearingRoundDeadlineScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingRoundDeadlineScheduler.class);

    private final HearingRoundService hearingRoundService;

    public HearingRoundDeadlineScheduler(HearingRoundService hearingRoundService) {
        this.hearingRoundService = hearingRoundService;
    }

    @Scheduled(fixedDelayString = "${dispute.hearing-round-timeout-scan-delay:PT15S}")
    public void expireDueRounds() {
        int expired = hearingRoundService.expireDueRounds();
        if (expired > 0) {
            LOGGER.info("Expired due hearing rounds: count={}", expired);
        }
    }
}
