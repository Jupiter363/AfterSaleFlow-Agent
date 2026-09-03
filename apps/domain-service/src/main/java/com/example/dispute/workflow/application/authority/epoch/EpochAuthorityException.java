package com.example.dispute.workflow.application.authority.epoch;

/** Non-retryable authority invariant failure. */
public final class EpochAuthorityException extends RuntimeException {

    private final String reasonCode;

    public EpochAuthorityException(String reasonCode, String message) {
        super(message);
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
