package com.example.dispute.workflow.application.projection;

public class DomainOperationConflictException extends RuntimeException {

    private final String reasonCode;

    public DomainOperationConflictException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
