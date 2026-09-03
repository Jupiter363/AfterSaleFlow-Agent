package com.example.dispute.workflow.infrastructure.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.orchestration.domain-event-recovery.enabled",
        havingValue = "true")
public class CaseDomainEventRecoveryScheduler {

    private final CaseDomainEventRecoveryRelay relay;

    public CaseDomainEventRecoveryScheduler(CaseDomainEventRecoveryRelay relay) {
        this.relay = relay;
    }

    public void recoverMissedEvents() {
        relay.recoverAvailable();
    }
}
