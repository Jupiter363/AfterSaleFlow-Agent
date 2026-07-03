package com.example.dispute.workflow.domain;

import java.util.List;

public record DeliberationPanelCommand(
        String caseId,
        String workflowId,
        String draftId,
        long dossierVersion,
        List<String> selectedCritics,
        List<String> triggerReasons) {

    public DeliberationPanelCommand {
        selectedCritics =
                selectedCritics == null ? List.of() : List.copyOf(selectedCritics);
        triggerReasons =
                triggerReasons == null ? List.of() : List.copyOf(triggerReasons);
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
                List.of());
    }
}
