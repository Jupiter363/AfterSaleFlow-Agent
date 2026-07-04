package com.example.dispute.workflow.domain;

import java.util.List;

public record CriticActivityResult(
        String critic,
        String status,
        String severity,
        List<String> blockingIssues,
        String frozenInputFingerprint,
        int score) {

    public CriticActivityResult {
        blockingIssues =
                blockingIssues == null
                        ? List.of()
                        : List.copyOf(blockingIssues);
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("critic score must be between 0 and 100");
        }
    }

    public CriticActivityResult(
            String critic,
            String status,
            String severity,
            List<String> blockingIssues,
            String frozenInputFingerprint) {
        this(
                critic,
                status,
                severity,
                blockingIssues,
                frozenInputFingerprint,
                defaultScore(status, severity));
    }

    private static int defaultScore(String status, String severity) {
        if (!"COMPLETED".equals(status)) {
            return 0;
        }
        return switch (severity == null ? "" : severity) {
            case "BLOCKER" -> 0;
            case "HIGH" -> 79;
            default -> 100;
        };
    }
}
