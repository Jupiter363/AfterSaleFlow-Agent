package com.example.dispute.workflow.infrastructure.outbox;

import java.util.Objects;

public final class TemporalUpdateDeliveryException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    private TemporalUpdateDeliveryException(
            String errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
        this.retryable = retryable;
        if (errorCode.isBlank() || errorCode.length() > 64) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
    }

    public static TemporalUpdateDeliveryException retryable(
            String errorCode, String message, Throwable cause) {
        return new TemporalUpdateDeliveryException(errorCode, true, message, cause);
    }

    public static TemporalUpdateDeliveryException permanent(
            String errorCode, String message, Throwable cause) {
        return new TemporalUpdateDeliveryException(errorCode, false, message, cause);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
