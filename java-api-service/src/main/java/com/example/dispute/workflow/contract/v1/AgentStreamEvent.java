package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentStreamEvent(
        String schemaVersion,
        String runId,
        String attemptId,
        long sequenceNo,
        StreamEventType eventType,
        Audience audience,
        Instant occurredAt,
        Payload payload) {

    public AgentStreamEvent {
        schemaVersion = version(schemaVersion, "agent-stream.v2");
        required(runId, "runId");
        required(attemptId, "attemptId");
        required(eventType, "eventType");
        required(audience, "audience");
        required(occurredAt, "occurredAt");
        required(payload, "payload");
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Payload(
            String node,
            String field,
            String delta,
            Usage usage,
            String reasonCode,
            String resetAttemptId,
            String finalResultRef,
            String finalResultHash,
            String errorCode,
            Boolean retryable) {}
}
