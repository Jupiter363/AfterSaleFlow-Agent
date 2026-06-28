package com.example.dispute.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record EvaluationReportView(
        String evaluationTraceId,
        String caseId,
        int evaluationVersion,
        String evaluationStatus,
        String evaluatorModel,
        String promptVersion,
        JsonNode metricScores,
        JsonNode findings,
        JsonNode report,
        Long latencyMs,
        Integer tokenUsage,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt) {}
