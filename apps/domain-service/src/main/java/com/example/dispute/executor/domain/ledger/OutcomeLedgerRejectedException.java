package com.example.dispute.executor.domain.ledger;

/** Fail-closed operation identity, authority, transition, or receipt rejection. */
public final class OutcomeLedgerRejectedException extends RuntimeException {

    private final String code;

    public OutcomeLedgerRejectedException(String code, String message) {
        super(message);
        this.code = OutcomeLedgerValues.identifier(code, "code", 96);
    }

    public OutcomeLedgerRejectedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = OutcomeLedgerValues.identifier(code, "code", 96);
    }

    public String code() {
        return code;
    }
}
