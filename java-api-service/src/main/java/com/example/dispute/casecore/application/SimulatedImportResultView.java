package com.example.dispute.casecore.application;

import java.util.List;

public record SimulatedImportResultView(List<ImportedDisputeView> items) {

    public SimulatedImportResultView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
