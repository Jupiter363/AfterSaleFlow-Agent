package com.example.dispute.workflow.targete2e.temporal.intake.finalizationread;

/** Retryable persistence failure surfaced to the Temporal Activity retry policy. */
public final class TargetE2eFinalizationReadPersistenceException extends RuntimeException {

    public TargetE2eFinalizationReadPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
