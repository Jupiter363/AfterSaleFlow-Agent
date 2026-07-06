package com.example.dispute.room.application;

public interface EvidenceAgentTurnClient {
    EvidenceAgentTurnResult run(
            EvidenceAgentTurnCommand command, String traceId, String requestId);
}
