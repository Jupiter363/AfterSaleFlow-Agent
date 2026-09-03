package com.example.dispute.workflow.infrastructure.agent;

/** Internal graph-command transport failure; response content is never included. */
public final class GraphCommandTransportException extends RuntimeException {

    public enum Kind {
        NOT_SUBMITTED,
        TRANSPORT,
        PROTOCOL
    }

    private final Kind kind;

    public GraphCommandTransportException(String message, Throwable cause) {
        this(message, cause, Kind.TRANSPORT);
    }

    private GraphCommandTransportException(String message, Throwable cause, Kind kind) {
        super(message, cause);
        this.kind = kind;
    }

    public static GraphCommandTransportException protocolViolation(String message) {
        return new GraphCommandTransportException(message, null, Kind.PROTOCOL);
    }

    public static GraphCommandTransportException notSubmitted(
            String message, Throwable cause) {
        return new GraphCommandTransportException(message, cause, Kind.NOT_SUBMITTED);
    }

    public Kind kind() {
        return kind;
    }

    public boolean protocolViolation() {
        return kind == Kind.PROTOCOL;
    }

    public boolean notSubmitted() {
        return kind == Kind.NOT_SUBMITTED;
    }
}
