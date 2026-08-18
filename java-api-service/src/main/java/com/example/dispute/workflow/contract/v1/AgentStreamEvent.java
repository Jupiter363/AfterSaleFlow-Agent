package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.JsonNode;
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
        if (!"agent-stream.v2".equals(schemaVersion)
                && !"agent-stream.v3".equals(schemaVersion)) {
            throw new IllegalArgumentException("schema_version must be agent-stream.v3");
        }
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
            Boolean retryable,
            String frameId,
            Integer frameSequence,
            String frameType,
            JsonNode publicHeader,
            Integer deltaIndex,
            String publicText,
            String durableCursor,
            String headerSha256,
            String publicTextSha256,
            String frameSha256,
            Integer publicTextChars) {

        /** Legacy positional constructor retained for source-level callers while the v3
         * activation uses the frame fields below. It does not authorize v2 at runtime. */
        public Payload(
                String node,
                String field,
                String delta,
                Usage usage,
                String reasonCode,
                String resetAttemptId,
                String finalResultRef,
                String finalResultHash,
                String errorCode,
                Boolean retryable) {
            this(node, field, delta, usage, reasonCode, resetAttemptId, finalResultRef,
                    finalResultHash, errorCode, retryable, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
    }
}
