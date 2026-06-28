package com.example.dispute.evaluation.application;

public record EvaluationMetricsView(
        long totalEvaluations,
        long completedEvaluations,
        double draftApprovalRate,
        double reviewerModificationRate) {}
