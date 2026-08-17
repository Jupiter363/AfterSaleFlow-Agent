package com.example.dispute.evidence.infrastructure;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.evidence.application.EvidenceParseDeliveryTrigger;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Starts exact durable parse delivery only after the upload transaction commits. */
@Component
public final class PostCommitEvidenceParseDeliveryTrigger implements EvidenceParseDeliveryTrigger {
    private final PostCommitSideEffectExecutor postCommit;
    private final EvidenceParseOutboxDispatcher dispatcher;

    public PostCommitEvidenceParseDeliveryTrigger(
            PostCommitSideEffectExecutor postCommit, EvidenceParseOutboxDispatcher dispatcher) {
        this.postCommit = postCommit;
        this.dispatcher = dispatcher;
    }

    @Override
    public void deliveryRequested(String outboxId) {
        if (outboxId == null || outboxId.isBlank()) {
            throw new IllegalArgumentException("evidence parse outbox id is required");
        }
        postCommit.execute(
                "evidence-parse-outbox-delivery",
                Map.of("outbox_id", outboxId),
                () -> dispatcher.dispatchNow(outboxId));
    }
}
