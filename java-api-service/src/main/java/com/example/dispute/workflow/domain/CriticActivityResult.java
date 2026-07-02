package com.example.dispute.workflow.domain;

import java.util.List;

public record CriticActivityResult(
        String critic,
        String status,
        String severity,
        List<String> blockingIssues,
        String frozenInputFingerprint) {

    public CriticActivityResult {
        blockingIssues =
                blockingIssues == null
                        ? List.of()
                        : List.copyOf(blockingIssues);
    }
}
