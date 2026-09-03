package com.example.dispute.workflow.targete2e.persistence;

/** Fail-closed persistence error with a stable admission/replay code. */
public final class TargetE2EPersistenceException extends RuntimeException {

    private final String code;

    public TargetE2EPersistenceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TargetE2EPersistenceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
