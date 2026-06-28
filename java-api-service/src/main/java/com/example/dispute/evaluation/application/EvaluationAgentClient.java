package com.example.dispute.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;

public interface EvaluationAgentClient {

    EvaluationAgentResult analyze(
            JsonNode closedCaseSnapshot, String traceId, String requestId);
}
