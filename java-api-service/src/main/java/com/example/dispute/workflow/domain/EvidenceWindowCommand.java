package com.example.dispute.workflow.domain;

import java.time.Duration;

public record EvidenceWindowCommand(String caseId, Duration window) {
    public EvidenceWindowCommand {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }
}
