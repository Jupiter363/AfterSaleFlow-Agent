package com.example.dispute.workflow.domain;

import java.util.List;

public record DeliberationPanelCommand(
        String caseId,
        String workflowId,
        String draftId,
        long dossierVersion,
        List<String> selectedCritics) {

    public DeliberationPanelCommand {
        selectedCritics =
                selectedCritics == null ? List.of() : List.copyOf(selectedCritics);
    }
}
