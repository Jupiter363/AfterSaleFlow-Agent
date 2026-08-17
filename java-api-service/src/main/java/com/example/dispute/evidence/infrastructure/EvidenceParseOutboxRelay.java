package com.example.dispute.evidence.infrastructure;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovery path for durable parse rows missed by post-commit best-effort wakeup. */
@Component
public final class EvidenceParseOutboxRelay {
    private final EvidenceParseOutboxDispatcher dispatcher;

    public EvidenceParseOutboxRelay(EvidenceParseOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${dispute.evidence-parse-outbox.poll-interval:PT5S}")
    public void recoverDeliveries() {
        dispatcher.dispatchAvailable();
    }
}
