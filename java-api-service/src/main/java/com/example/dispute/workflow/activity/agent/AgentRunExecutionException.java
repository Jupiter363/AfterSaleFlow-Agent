package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import java.util.Objects;
import java.util.regex.Pattern;

/** Sanitized failure raised by the Python execution adapter. */
public final class AgentRunExecutionException extends RuntimeException {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private final String errorCode;
    private final AgentRunRecoveryAction recoveryAction;
    private final long lastSequenceNo;
    private final boolean publicOutputEmitted;

    public AgentRunExecutionException(
            String errorCode,
            String internalMessage,
            AgentRunRecoveryAction recoveryAction,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Throwable cause) {
        super(internalMessage == null ? "agent run execution failed" : internalMessage, cause);
        if (!ERROR_CODE.matcher(Objects.requireNonNull(errorCode, "errorCode")).matches()) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
        if (lastSequenceNo < -1) {
            throw new IllegalArgumentException("lastSequenceNo is below the empty stream baseline");
        }
        if (lastSequenceNo == -1 && publicOutputEmitted) {
            throw new IllegalArgumentException(
                    "empty stream baseline cannot have public output");
        }
        if (lastSequenceNo == -1
                && recoveryAction == AgentRunRecoveryAction.RECONCILE_TERMINAL) {
            throw new IllegalArgumentException(
                    "terminal reconciliation requires a durable stream event");
        }
        this.errorCode = errorCode;
        this.recoveryAction =
                Objects.requireNonNull(recoveryAction, "recoveryAction must not be null");
        this.lastSequenceNo = lastSequenceNo;
        this.publicOutputEmitted = publicOutputEmitted;
    }

    public static AgentRunExecutionException retrySameCommand(
            String errorCode,
            String internalMessage,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Throwable cause) {
        return new AgentRunExecutionException(
                errorCode,
                internalMessage,
                AgentRunRecoveryAction.RETRY_SAME_COMMAND,
                lastSequenceNo,
                publicOutputEmitted,
                cause);
    }

    public static AgentRunExecutionException createNextAttempt(
            String errorCode,
            String internalMessage,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Throwable cause) {
        return new AgentRunExecutionException(
                errorCode,
                internalMessage,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                lastSequenceNo,
                publicOutputEmitted,
                cause);
    }

    public static AgentRunExecutionException reconcileTerminal(
            String errorCode,
            String internalMessage,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Throwable cause) {
        return new AgentRunExecutionException(
                errorCode,
                internalMessage,
                AgentRunRecoveryAction.RECONCILE_TERMINAL,
                lastSequenceNo,
                publicOutputEmitted,
                cause);
    }

    public static AgentRunExecutionException failLogicalRun(
            String errorCode,
            String internalMessage,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            Throwable cause) {
        return new AgentRunExecutionException(
                errorCode,
                internalMessage,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                lastSequenceNo,
                publicOutputEmitted,
                cause);
    }

    public String errorCode() {
        return errorCode;
    }

    /** Compatibility summary only; recovery decisions must use {@link #recoveryAction()}. */
    public boolean retryable() {
        return recoveryAction != AgentRunRecoveryAction.FAIL_LOGICAL_RUN;
    }

    /** Compatibility summary only; recovery decisions must use {@link #recoveryAction()}. */
    public boolean commandReplaySafe() {
        return recoveryAction == AgentRunRecoveryAction.RETRY_SAME_COMMAND
                || recoveryAction == AgentRunRecoveryAction.RECONCILE_TERMINAL;
    }

    public AgentRunRecoveryAction recoveryAction() {
        return recoveryAction;
    }

    public long lastSequenceNo() {
        return lastSequenceNo;
    }

    public boolean publicOutputEmitted() {
        return publicOutputEmitted;
    }
}
