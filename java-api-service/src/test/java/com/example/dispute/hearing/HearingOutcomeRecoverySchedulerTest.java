package com.example.dispute.hearing;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.application.HearingFinalRoundRecoveryService;
import com.example.dispute.hearing.application.HearingOutcomeOrchestrationService;
import com.example.dispute.hearing.application.HearingOutcomeRecoveryScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class HearingOutcomeRecoverySchedulerTest {

    private final HearingFinalRoundRecoveryService finalRoundRecoveryService =
            mock(HearingFinalRoundRecoveryService.class);
    private final HearingOutcomeOrchestrationService outcomeService =
            mock(HearingOutcomeOrchestrationService.class);
    private final HearingOutcomeRecoveryScheduler scheduler =
            new HearingOutcomeRecoveryScheduler(finalRoundRecoveryService, outcomeService);

    @Test
    void recoversFinalRoundConvergenceBeforeCompletedOutcomeReview() {
        scheduler.recover();

        InOrder order = inOrder(finalRoundRecoveryService, outcomeService);
        order.verify(finalRoundRecoveryService).recoverFinalRoundsWithoutDraft(20);
        order.verify(outcomeService).recoverCompletedHearingsWithoutReview(20);
    }

    @Test
    void outcomeRecoveryStillRunsWhenFinalRoundRecoveryFails() {
        when(finalRoundRecoveryService.recoverFinalRoundsWithoutDraft(20))
                .thenThrow(new IllegalStateException("final round recovery unavailable"));

        scheduler.recover();

        verify(outcomeService).recoverCompletedHearingsWithoutReview(20);
    }
}
