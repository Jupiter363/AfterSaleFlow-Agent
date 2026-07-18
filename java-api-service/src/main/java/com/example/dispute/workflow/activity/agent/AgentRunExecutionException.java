package com.example.dispute.workflow.activity.agent;

import java.util.Objects;
import java.util.regex.Pattern;

/** Sanitized failure raised by the Python execution adapter. */
public final class AgentRunExecutionException extends RuntimeException {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private final String errorCode;
    private final boolean retryable;
    private final boolean commandReplaySafe;
    private final long lastSequenceNo;
    private final boolean publicOutputEmitted;

    public AgentRunExecutionException(
            String errorCode,
            String internalMessage,
            boolean retryable,
            boolean commandReplaySafe,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Throwable cause) {
        super(internalMessage == null ? "agent run execution failed" : internalMessage, cause);
        if (!ERROR_CODE.matcher(Objects.requireNonNull(errorCode, "errorCode")).matches()) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
        if (lastSequenceNo < 0) {
            throw new IllegalArgumentException("lastSequenceNo must not be negative");
        }
        if (commandReplaySafe && !retryable) {
            throw new IllegalArgumentException("commandReplaySafe requires a retryable failure");
        }
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.commandReplaySafe = commandReplaySafe;
        this.lastSequenceNo = lastSequenceNo;
        this.publicOutputEmitted = publicOutputEmitted;
    }

    public static AgentRunExecutionException retryable(
            String errorCode,
            String internalMessage,
            boolean commandReplaySafe,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Throwable cause) {
        return new AgentRunExecutionException(
                errorCode,
                internalMessage,
                true,
                commandReplaySafe,
                lastSequenceNo,
                publicOutputEmitted,
                cause);
    }

    public static AgentRunExecutionException nonRetryable(
            String errorCode,
            String internalMessage,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Throwable cause) {
        return new AgentRunExecutionException(
                errorCode,
                internalMessage,
                false,
                false,
                lastSequenceNo,
                publicOutputEmitted,
                cause);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    /** True only when Python guarantees this exact command returns its cached result. */
    public boolean commandReplaySafe() {
        return commandReplaySafe;
    }

    public long lastSequenceNo() {
        return lastSequenceNo;
    }

    public boolean publicOutputEmitted() {
        return publicOutputEmitted;
    }
}
