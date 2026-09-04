package com.example.dispute.workflow.runtime.persistence;

/** Fail-closed persistence error with a stable admission/replay code. */
public final class ProductionPersistenceException extends RuntimeException {

    private final String code;

    public ProductionPersistenceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ProductionPersistenceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
