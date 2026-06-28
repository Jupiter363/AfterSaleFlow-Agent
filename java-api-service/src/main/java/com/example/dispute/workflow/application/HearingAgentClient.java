package com.example.dispute.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;

public interface HearingAgentClient {
    HearingAgentResult analyze(
            JsonNode request, String traceId, String requestId);
}
