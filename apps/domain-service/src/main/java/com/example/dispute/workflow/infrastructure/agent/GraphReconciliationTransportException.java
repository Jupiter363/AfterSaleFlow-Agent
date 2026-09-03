package com.example.dispute.workflow.infrastructure.agent;

/** Internal transport failure; response content is deliberately excluded from the exception. */
public final class GraphReconciliationTransportException extends RuntimeException {

    private final boolean protocolViolation;

    public GraphReconciliationTransportException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private GraphReconciliationTransportException(
            String message,
            Throwable cause,
            boolean protocolViolation) {
        super(message, cause);
        this.protocolViolation = protocolViolation;
    }

    public static GraphReconciliationTransportException protocolViolation(String message) {
        return new GraphReconciliationTransportException(message, null, true);
    }

    public boolean protocolViolation() {
        return protocolViolation;
    }
}
