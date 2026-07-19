package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import java.util.Objects;
import java.util.regex.Pattern;

/** Sanitized result-reconciliation failure with a closed recovery action. */
public final class GraphReconciliationException extends RuntimeException {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private final String errorCode;
    private final int httpStatus;
    private final boolean retryable;
    private final AgentRunRecoveryAction recoveryAction;

    public GraphReconciliationException(
            String errorCode,
            int httpStatus,
            boolean retryable,
            AgentRunRecoveryAction recoveryAction,
            String internalMessage,
            Throwable cause) {
        super(internalMessage == null ? "Graph reconciliation failed" : internalMessage, cause);
        if (!CODE.matcher(Objects.requireNonNull(errorCode, "errorCode")).matches()) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
        if (httpStatus < 0 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus is invalid");
        }
        this.recoveryAction = Objects.requireNonNull(recoveryAction, "recoveryAction");
        if (recoveryAction == AgentRunRecoveryAction.RETRY_SAME_COMMAND && !retryable) {
            throw new IllegalArgumentException("RETRY_SAME_COMMAND must be retryable");
        }
        if (recoveryAction != AgentRunRecoveryAction.RETRY_SAME_COMMAND && retryable) {
            throw new IllegalArgumentException("only RETRY_SAME_COMMAND may be retryable");
        }
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public static GraphReconciliationException transport(Throwable cause) {
        return new GraphReconciliationException(
                "GRAPH_RECONCILIATION_TRANSPORT_FAILED",
                0,
                true,
                AgentRunRecoveryAction.RETRY_SAME_COMMAND,
                "Graph reconciliation transport failed",
                cause);
    }

    public static GraphReconciliationException protocol(String message, Throwable cause) {
        return new GraphReconciliationException(
                "GRAPH_RECONCILIATION_PROTOCOL_REJECTED",
                0,
                false,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                message,
                cause);
    }

    public String errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    public AgentRunRecoveryAction recoveryAction() {
        return recoveryAction;
    }
}
