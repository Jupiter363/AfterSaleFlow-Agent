package com.example.dispute.workflow.application.projection;

public class ProjectionWriteRejectedException extends RuntimeException {

    private final String reasonCode;

    public ProjectionWriteRejectedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
