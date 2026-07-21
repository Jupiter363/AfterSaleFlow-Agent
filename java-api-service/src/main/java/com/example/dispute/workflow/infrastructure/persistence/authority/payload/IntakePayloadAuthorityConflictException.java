package com.example.dispute.workflow.infrastructure.persistence.authority.payload;

/** Raised when a payload, command, or outbox idempotency key is already bound to another tuple. */
public final class IntakePayloadAuthorityConflictException extends RuntimeException {

    public IntakePayloadAuthorityConflictException(String message) {
        super(message);
    }
}
