package com.example.dispute.workflow.recovery;

import static org.mockito.Mockito.verify;

import com.example.dispute.workflow.infrastructure.recovery.CaseDomainEventRecoveryRelay;
import com.example.dispute.workflow.infrastructure.recovery.CaseDomainEventRecoveryScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CaseDomainEventRecoverySchedulerTest {

    @Mock private CaseDomainEventRecoveryRelay relay;

    @Test
    void scheduledEntryInvokesTheDurableRecoveryRelay() {
        CaseDomainEventRecoveryScheduler scheduler =
                new CaseDomainEventRecoveryScheduler(relay);

        scheduler.recoverMissedEvents();

        verify(relay).recoverAvailable();
    }
}
