package com.example.dispute.workflow.application.intake;

/** Non-retryable schema, authority, fencing, or domain rejection at the formal boundary. */
public final class IntakeFinalizationRejectedException extends RuntimeException {

    private final String code;

    public IntakeFinalizationRejectedException(String code, String message) {
        super(message);
        this.code = IntakeContractSupport.identifier(code, "code");
    }

    public IntakeFinalizationRejectedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = IntakeContractSupport.identifier(code, "code");
    }

    public String code() {
        return code;
    }
}
