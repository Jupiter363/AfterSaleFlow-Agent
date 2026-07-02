package com.example.dispute.workflow.domain;

import java.util.List;

public record DeliberationPanelResult(
        String deliberationId,
        String panelResult,
        boolean revisionRequired,
        boolean manualRequired,
        List<String> majorObjections,
        List<String> unavailableCritics) {

    public DeliberationPanelResult {
        majorObjections =
                majorObjections == null ? List.of() : List.copyOf(majorObjections);
        unavailableCritics =
                unavailableCritics == null
                        ? List.of()
                        : List.copyOf(unavailableCritics);
    }
}
