package com.example.dispute.workflow.domain;

import java.util.List;

public record DeliberationPanelCommand(
        String caseId,
        String workflowId,
        String draftId,
        long dossierVersion,
        List<String> selectedCritics,
        List<String> triggerReasons,
        int scoreThreshold,
        int maxRevisionAttempts) {

    public DeliberationPanelCommand {
        selectedCritics =
                selectedCritics == null ? List.of() : List.copyOf(selectedCritics);
        triggerReasons =
                triggerReasons == null ? List.of() : List.copyOf(triggerReasons);
        scoreThreshold = DeliberationPolicy.validateScoreThreshold(scoreThreshold);
        maxRevisionAttempts =
                DeliberationPolicy.validateMaxRegenerations(maxRevisionAttempts);
    }

    public DeliberationPanelCommand(
            String caseId,
            String workflowId,
            String draftId,
            long dossierVersion,
            List<String> selectedCritics) {
        this(
                caseId,
                workflowId,
                draftId,
                dossierVersion,
                selectedCritics,
                List.of(),
                80,
                2);
    }

    public DeliberationPanelCommand(
            String caseId,
            String workflowId,
            String draftId,
            long dossierVersion,
            List<String> selectedCritics,
            List<String> triggerReasons) {
        this(
                caseId,
                workflowId,
                draftId,
                dossierVersion,
                selectedCritics,
                triggerReasons,
                80,
                2);
    }
}
