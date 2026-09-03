package com.example.dispute.workflow.application.projection;

import java.util.Objects;

public record ProcessProjectionReconciliationResult(
        Outcome outcome,
        String reasonCode,
        String issueKey,
        long actualProcessRevision,
        long authoritativeProcessRevision) {

    public ProcessProjectionReconciliationResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (actualProcessRevision < -1 || authoritativeProcessRevision < -1) {
            throw new IllegalArgumentException("reconciliation revisions are invalid");
        }
    }

    public enum Outcome {
        CONSISTENT,
        DRIFT_DETECTED,
        REPAIRED,
        REPAIR_REJECTED,
        SOURCE_INCOMPLETE,
        SOURCE_UNAVAILABLE,
        NOT_OWNED
    }
}
