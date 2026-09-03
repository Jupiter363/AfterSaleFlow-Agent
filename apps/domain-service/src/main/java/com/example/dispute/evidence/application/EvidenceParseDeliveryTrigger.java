package com.example.dispute.evidence.application;

/** Requests best-effort immediate delivery of one already-durable parser outbox row. */
public interface EvidenceParseDeliveryTrigger {
    void deliveryRequested(String outboxId);
}
