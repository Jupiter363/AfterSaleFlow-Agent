package com.example.dispute.workflow.runtime.temporal.intake.finalizationread;

/** Retryable persistence failure surfaced to the Temporal Activity retry policy. */
public final class ProductionFinalizationReadPersistenceException extends RuntimeException {

    public ProductionFinalizationReadPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
