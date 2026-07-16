package com.example.dispute.hearing.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Converts both missing party actions to terminal timeout rows at the shared deadline. */
@Component
public class HearingFlowDeadlineScheduler {

    private final HearingFlowRuntimeService runtimeService;

    public HearingFlowDeadlineScheduler(HearingFlowRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Scheduled(fixedDelayString = "${dispute.hearing-flow-timeout-scan-delay:PT15S}")
    public void expireDueStages() {
        runtimeService.expireDuePartyStages();
    }
}
