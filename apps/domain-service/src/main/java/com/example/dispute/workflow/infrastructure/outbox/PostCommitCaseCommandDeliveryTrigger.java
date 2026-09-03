package com.example.dispute.workflow.infrastructure.outbox;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.workflow.application.command.CaseCommandDeliveryTrigger;
import java.util.Map;

public final class PostCommitCaseCommandDeliveryTrigger
        implements CaseCommandDeliveryTrigger {

    private final PostCommitSideEffectExecutor postCommit;
    private final TemporalCommandDispatcher dispatcher;

    public PostCommitCaseCommandDeliveryTrigger(
            PostCommitSideEffectExecutor postCommit,
            TemporalCommandDispatcher dispatcher) {
        this.postCommit = postCommit;
        this.dispatcher = dispatcher;
    }

    @Override
    public void deliveryRequested(String outboxId) {
        postCommit.execute(
                "temporal-command-update-with-start",
                Map.of("outbox_id", outboxId),
                () -> dispatcher.dispatchNow(outboxId));
    }
}
