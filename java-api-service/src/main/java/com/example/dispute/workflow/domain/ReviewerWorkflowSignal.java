package com.example.dispute.workflow.domain;

public record ReviewerWorkflowSignal(String reviewerId, String decision, String reason) {

    public ReviewerWorkflowSignal {
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new IllegalArgumentException("reviewerId must not be blank");
        }
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("decision must not be blank");
        }
    }
}
