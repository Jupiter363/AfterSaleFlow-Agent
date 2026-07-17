package com.example.dispute.workflow.infrastructure.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.orchestration.command-outbox.enabled",
        havingValue = "true")
public class TemporalCommandOutboxRelay {

    private final TemporalCommandDispatcher dispatcher;

    public TemporalCommandOutboxRelay(TemporalCommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.orchestration.command-outbox.poll-interval:PT5S}")
    public void recoverDeliveries() {
        dispatcher.dispatchAvailable();
    }
}
