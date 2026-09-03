package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;

/** Fail-closed rejection at the isolated target-E2E finalization boundary. */
public final class TargetE2eFinalizationRejectedException extends RuntimeException
        implements AgentRunFinalizationFailure {

    private final String code;

    public TargetE2eFinalizationRejectedException(String code, String message) {
        super(message);
        this.code = required(code, "code");
    }

    public TargetE2eFinalizationRejectedException(
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
