package com.example.dispute.workflow.application;

import java.util.Objects;

/** Classifies whether a durable AgentRun attempt may retry Temporal admission unchanged. */
public final class AgentRunV2WorkflowLaunchException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    private AgentRunV2WorkflowLaunchException(
            String code, boolean retryable, RuntimeException cause) {
        super(code + ": " + cause.getClass().getSimpleName(), cause);
        this.code = requireCode(code);
        this.retryable = retryable;
    }

    public static AgentRunV2WorkflowLaunchException permanent(String code, RuntimeException cause) {
        return new AgentRunV2WorkflowLaunchException(
                code, false, Objects.requireNonNull(cause, "cause"));
    }

    public static AgentRunV2WorkflowLaunchException retryable(String code, RuntimeException cause) {
        return new AgentRunV2WorkflowLaunchException(
                code, true, Objects.requireNonNull(cause, "cause"));
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    private static String requireCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("launch failure code is invalid");
        }
        return code;
    }
}
