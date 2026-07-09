package com.example.dispute.hearing.application;

import java.time.Instant;

public record AgentA2AMessageView(
        String a2aMessageId,
        String caseId,
        int roundNo,
        String fromAgent,
        String toAgent,
        String messageType,
        String inputRefsJson,
        String payloadJson,
        String visibility,
        String agentRunId,
        Instant createdAt) {}
