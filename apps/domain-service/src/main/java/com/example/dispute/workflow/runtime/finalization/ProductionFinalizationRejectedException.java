package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;

/** Fail-closed rejection at the isolated production-runtime finalization boundary. */
public final class ProductionFinalizationRejectedException extends RuntimeException
        implements AgentRunFinalizationFailure {

    private final String code;

    public ProductionFinalizationRejectedException(String code, String message) {
        super(message);
        this.code = required(code, "code");
    }

    public ProductionFinalizationRejectedException(
            String code, String message, Throwable cause) {
        super(message, cause);
        this.code = required(code, "code");
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public boolean retryable() {
        return false;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
