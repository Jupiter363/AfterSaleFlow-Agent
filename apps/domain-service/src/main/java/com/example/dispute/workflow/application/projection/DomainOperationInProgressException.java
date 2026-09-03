package com.example.dispute.workflow.application.projection;

public class DomainOperationInProgressException extends RuntimeException {

    public DomainOperationInProgressException(String message) {
        super(message);
    }
}
