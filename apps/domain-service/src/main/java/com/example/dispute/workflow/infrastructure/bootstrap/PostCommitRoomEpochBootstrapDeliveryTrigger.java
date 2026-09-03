package com.example.dispute.workflow.infrastructure.bootstrap;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.workflow.application.epoch.RoomEpochBootstrapDeliveryTrigger;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class PostCommitRoomEpochBootstrapDeliveryTrigger
        implements RoomEpochBootstrapDeliveryTrigger {

    private final PostCommitSideEffectExecutor postCommit;
    private final RoomEpochBootstrapDispatcher dispatcher;

    public PostCommitRoomEpochBootstrapDeliveryTrigger(
            PostCommitSideEffectExecutor postCommit,
            RoomEpochBootstrapDispatcher dispatcher) {
        this.postCommit = postCommit;
        this.dispatcher = dispatcher;
    }

    @Override
    public void deliveryRequested(String outboxId) {
        if (outboxId == null || outboxId.isBlank()) {
            throw new IllegalArgumentException("outboxId must not be blank");
        }
        postCommit.execute(
                "room-epoch-bootstrap-update-with-start",
                Map.of("outbox_id", outboxId),
                () -> dispatcher.dispatchNow(outboxId));
    }
}
