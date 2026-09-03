package com.example.dispute.workflow.application.intake;

import java.util.Optional;

/** Reads an already committed Intake operation without reopening the formal write path. */
@FunctionalInterface
public interface IntakeFinalizationReceiptReader {

    Optional<IntakeFinalizationReceipt> findCommitted(
            String tenantSurrogate, String operationKey, String requestHash);
}
