package com.example.dispute.workflow.application.authority.epoch;

/** Persistence port for the durable room bootstrap outbox. */
public interface EpochBootstrapOutboxPublisher {

    String publish(EpochBootstrapOutbox outbox);
}
