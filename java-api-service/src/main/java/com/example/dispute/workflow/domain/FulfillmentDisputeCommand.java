package com.example.dispute.workflow.domain;

import com.example.dispute.domain.model.RouteType;
import java.time.Duration;
import java.util.Objects;

public record FulfillmentDisputeCommand(
        String caseId,
        String workflowId,
        RouteType routeType,
        long dossierVersion,
        Duration evidenceWaitTimeout,
        Duration reviewWaitTimeout,
        int maxEvidenceRounds,
        boolean deliberationRequired) {

    public FulfillmentDisputeCommand {
        requireText(caseId, "caseId");
        requireText(workflowId, "workflowId");
        Objects.requireNonNull(routeType, "routeType must not be null");
        Objects.requireNonNull(
                evidenceWaitTimeout, "evidenceWaitTimeout must not be null");
        Objects.requireNonNull(
                reviewWaitTimeout, "reviewWaitTimeout must not be null");
        if (dossierVersion < 1) {
            throw new IllegalArgumentException("dossierVersion must be positive");
        }
        if (maxEvidenceRounds < 1 || maxEvidenceRounds > 5) {
            throw new IllegalArgumentException(
                    "maxEvidenceRounds must be between 1 and 5");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
