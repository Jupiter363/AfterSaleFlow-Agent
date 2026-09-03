package com.example.dispute.workflow.application.epoch;

/** Requests delivery of one exact durable room-epoch bootstrap outbox row. */
public interface RoomEpochBootstrapDeliveryTrigger {

    void deliveryRequested(String outboxId);
}
