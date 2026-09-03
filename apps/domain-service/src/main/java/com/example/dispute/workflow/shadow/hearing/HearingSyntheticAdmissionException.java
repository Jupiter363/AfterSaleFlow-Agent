package com.example.dispute.workflow.shadow.hearing;

/** Stable fail-closed rejection from the Hearing synthetic admission boundary. */
public final class HearingSyntheticAdmissionException extends IllegalArgumentException {

    private final String code;

    public HearingSyntheticAdmissionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public HearingSyntheticAdmissionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
