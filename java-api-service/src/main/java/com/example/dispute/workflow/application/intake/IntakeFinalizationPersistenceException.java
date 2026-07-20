package com.example.dispute.workflow.application.intake;

/** Retryable database/resource failure while committing an Intake formal result. */
public final class IntakeFinalizationPersistenceException extends RuntimeException {

    public IntakeFinalizationPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
