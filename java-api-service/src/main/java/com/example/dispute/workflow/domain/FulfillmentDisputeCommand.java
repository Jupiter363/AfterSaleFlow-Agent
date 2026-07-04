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
        String riskLevel,
        DeliberationInterventionMode deliberationMode,
        String deliberationMinimumRiskLevel,
        int deliberationScoreThreshold,
        int deliberationMaxRegenerations) {

    public FulfillmentDisputeCommand {
        requireText(caseId, "caseId");
        requireText(workflowId, "workflowId");
        Objects.requireNonNull(routeType, "routeType must not be null");
        requireText(riskLevel, "riskLevel");
        requireText(
                deliberationMinimumRiskLevel,
                "deliberationMinimumRiskLevel");
        Objects.requireNonNull(
                deliberationMode, "deliberationMode must not be null");
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
        DeliberationPolicy.validateScoreThreshold(deliberationScoreThreshold);
        DeliberationPolicy.validateMaxRegenerations(
                deliberationMaxRegenerations);
    }

    public FulfillmentDisputeCommand(
            String caseId,
            String workflowId,
            RouteType routeType,
            long dossierVersion,
            Duration evidenceWaitTimeout,
            Duration reviewWaitTimeout,
            int maxEvidenceRounds,
            boolean deliberationRequired) {
        this(
                caseId,
                workflowId,
                routeType,
                dossierVersion,
                evidenceWaitTimeout,
                reviewWaitTimeout,
                maxEvidenceRounds,
                deliberationRequired ? "HIGH" : "LOW",
                deliberationRequired
                        ? DeliberationInterventionMode.FINAL_ONLY
                        : DeliberationInterventionMode.DISABLED,
                "HIGH",
                80,
                2);
    }

    public boolean deliberationRequired() {
        return DeliberationPolicy.shouldRunFinalPanel(
                routeType,
                riskLevel,
                deliberationMode,
                deliberationMinimumRiskLevel);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
