package com.example.dispute.workflow.shadow.intake.admission;

/** Fail-closed error raised by the signed synthetic admission boundary. */
public final class IntakeSyntheticAdmissionException extends SecurityException {

    private final String code;

    public IntakeSyntheticAdmissionException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("admission error code is invalid");
        }
        this.code = code;
    }

    public IntakeSyntheticAdmissionException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("admission error code is invalid");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
