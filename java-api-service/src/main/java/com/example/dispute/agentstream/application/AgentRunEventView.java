package com.example.dispute.agentstream.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** Public dual-protocol event projection. Hidden model fields never enter this view. */
public record AgentRunEventView(
        String schemaVersion,
        String protocol,
        String runId,
        String attemptId,
        Long attemptNo,
        long sequence,
        String cursor,
        String type,
        String audience,
        String resetAttemptId,
        JsonNode payload,
        String operation,
        String nodeName,
        String field,
        String delta,
        JsonNode tokenUsage,
        String model,
        Long latencyMs,
        JsonNode response,
        String code,
        String message,
        Boolean retryable,
        Boolean visibleOutputEmitted,
        OffsetDateTime timestamp) {}
