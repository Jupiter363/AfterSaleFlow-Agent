package com.example.dispute.workflow.application.projection;

public final class ProjectionReconciliationRaceException extends RuntimeException {

    public ProjectionReconciliationRaceException(String message) {
        super(message);
    }
}
