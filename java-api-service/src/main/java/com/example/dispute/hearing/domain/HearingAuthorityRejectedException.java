package com.example.dispute.hearing.domain;

/** Fail-closed authority, idempotency, or durable-integrity rejection. */
public final class HearingAuthorityRejectedException extends RuntimeException {

    private final String code;

    public HearingAuthorityRejectedException(String code, String message) {
        super(message);
        this.code = HearingAuthorityExpectation.identifier(code, "code");
    }

    public HearingAuthorityRejectedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = HearingAuthorityExpectation.identifier(code, "code");
    }

    public String code() {
        return code;
    }
}
