package com.example.dispute.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;

public record EvaluationAgentResult(
        JsonNode report,
        String evaluatorModel,
        String promptVersion,
        long latencyMs,
        int tokenUsage) {}
