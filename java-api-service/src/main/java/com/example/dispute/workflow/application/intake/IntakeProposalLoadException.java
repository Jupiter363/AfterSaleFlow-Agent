package com.example.dispute.workflow.application.intake;

/** Retryable immutable-object access failure; the Activity may retry with its remaining budget. */
public final class IntakeProposalLoadException extends RuntimeException {

    public IntakeProposalLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
