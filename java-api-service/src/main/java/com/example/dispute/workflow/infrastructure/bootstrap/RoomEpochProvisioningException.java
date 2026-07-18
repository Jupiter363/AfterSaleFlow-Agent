package com.example.dispute.workflow.infrastructure.bootstrap;

public final class RoomEpochProvisioningException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    private RoomEpochProvisioningException(
            String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public static RoomEpochProvisioningException retryable(
            String errorCode, String message, Throwable cause) {
        return new RoomEpochProvisioningException(errorCode, message, true, cause);
    }

    public static RoomEpochProvisioningException permanent(
            String errorCode, String message, Throwable cause) {
        return new RoomEpochProvisioningException(errorCode, message, false, cause);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
