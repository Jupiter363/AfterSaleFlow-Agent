package com.example.dispute.workflow.domain;

import com.example.dispute.domain.model.RouteType;
import java.time.Duration;
import java.util.Objects;

public record CaseWorkflowInput(
        String caseId,
        String workflowId,
        RouteType routeType,
        Duration evidenceWaitTimeout,
        int maxEvidenceRounds) {

    public CaseWorkflowInput {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        Objects.requireNonNull(routeType, "routeType must not be null");
        Objects.requireNonNull(
                evidenceWaitTimeout, "evidenceWaitTimeout must not be null");
        if (evidenceWaitTimeout.isNegative() || evidenceWaitTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "evidenceWaitTimeout must be positive");
        }
        if (maxEvidenceRounds < 1 || maxEvidenceRounds > 5) {
            throw new IllegalArgumentException(
                    "maxEvidenceRounds must be between 1 and 5");
        }
    }
}
