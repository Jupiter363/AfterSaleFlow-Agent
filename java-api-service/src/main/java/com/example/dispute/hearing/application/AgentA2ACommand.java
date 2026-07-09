package com.example.dispute.hearing.application;

import java.util.Map;

public record AgentA2ACommand(
        String caseId,
        int roundNo,
        String fromAgent,
        String toAgent,
        String messageType,
        Map<String, Object> inputRefs,
        Map<String, Object> payload,
        String visibility,
        String agentRunId) {

    public AgentA2ACommand {
        if (roundNo < 1) {
            throw new IllegalArgumentException("roundNo must be positive");
        }
        inputRefs = inputRefs == null ? Map.of() : Map.copyOf(inputRefs);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
