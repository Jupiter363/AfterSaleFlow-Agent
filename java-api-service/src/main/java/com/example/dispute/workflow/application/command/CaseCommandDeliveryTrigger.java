package com.example.dispute.workflow.application.command;

@FunctionalInterface
public interface CaseCommandDeliveryTrigger {

    void deliveryRequested(String outboxId);
}
