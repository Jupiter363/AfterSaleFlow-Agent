package com.example.dispute.workflow.infrastructure.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.orchestration.room-epoch-bootstrap.enabled",
        havingValue = "true")
public final class RoomEpochBootstrapRelay {

    private final RoomEpochBootstrapDispatcher dispatcher;

    public RoomEpochBootstrapRelay(RoomEpochBootstrapDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void recoverBootstraps() {
        dispatcher.dispatchAvailable();
    }
}
