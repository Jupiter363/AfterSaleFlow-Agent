package com.example.dispute.workflow.temporal.caseprocess;

public record ProcessedCommandIdentity(
        String commandId, long caseCommandSequence, String requestHash) {

    public ProcessedCommandIdentity {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        if (caseCommandSequence < 1) {
            throw new IllegalArgumentException("caseCommandSequence must be positive");
        }
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash is invalid");
        }
    }
}
