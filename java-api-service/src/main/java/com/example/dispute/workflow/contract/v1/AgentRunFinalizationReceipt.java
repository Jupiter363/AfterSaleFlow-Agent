package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentRunFinalizationReceipt(
        String schemaVersion,
        String agentRunId,
        String logicalRunId,
        String attemptId,
        long attemptNo,
        long fencingToken,
        String finalResultHash,
        String manifestId,
        String manifestHash,
        long finalStreamSequenceNo,
        CommitStatus commitStatus,
        Instant committedAt) {

    public static final String SCHEMA_VERSION = "agent-run-finalization-receipt.v2";

    public AgentRunFinalizationReceipt {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        required(agentRunId, "agentRunId");
        required(logicalRunId, "logicalRunId");
        required(attemptId, "attemptId");
        if (attemptNo < 1 || fencingToken < 1 || finalStreamSequenceNo < 0) {
            throw new IllegalArgumentException("attempt, fence, or final sequence is invalid");
        }
        requireSha256(finalResultHash, "finalResultHash");
        required(manifestId, "manifestId");
        requireSha256(manifestHash, "manifestHash");
        required(commitStatus, "commitStatus");
        required(committedAt, "committedAt");
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
    }

    public enum CommitStatus {
        COMMITTED,
        ALREADY_COMMITTED
    }
}
