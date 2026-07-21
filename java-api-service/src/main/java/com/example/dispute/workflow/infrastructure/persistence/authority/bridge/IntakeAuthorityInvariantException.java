package com.example.dispute.workflow.infrastructure.persistence.authority.bridge;

/** Raised when an authority tuple is absent, ambiguous, stale, or internally inconsistent. */
public final class IntakeAuthorityInvariantException extends IllegalStateException {

    public IntakeAuthorityInvariantException(String message) {
        super(message);
    }

    public IntakeAuthorityInvariantException(String message, Throwable cause) {
        super(message, cause);
    }
}
