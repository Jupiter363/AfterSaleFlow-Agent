package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExecuteAgentRunResult(
        String schemaVersion,
        String agentRunId,
        String logicalRunId,
        String attemptId,
        long attemptNo,
        Outcome outcome,
        RoomGraphResult graphResult,
        String resultHash,
        long lastSequenceNo,
        boolean publicOutputEmitted,
        String errorCode,
        boolean retryable,
        AgentRunRecoveryAction recoveryAction,
        Instant completedAt) {

    public static final String SCHEMA_VERSION = "execute-agent-run-result.v3";

    public ExecuteAgentRunResult {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        required(agentRunId, "agentRunId");
        required(logicalRunId, "logicalRunId");
        required(attemptId, "attemptId");
        required(outcome, "outcome");
        if (!agentRunId.equals(logicalRunId)) {
            throw new IllegalArgumentException(
                    "agentRunId must equal logicalRunId");
        }
        if (attemptNo < 1 || lastSequenceNo < 0) {
            throw new IllegalArgumentException("attemptNo and lastSequenceNo are invalid");
        }
        if (outcome == Outcome.COMPLETED) {
            required(graphResult, "graphResult");
            requireSha256(resultHash, "resultHash");
            if (!logicalRunId.equals(graphResult.logicalRunId())
                    || !attemptId.equals(graphResult.attemptId())
                    || !resultHash.equals(graphResult.outputHash())) {
                throw new IllegalArgumentException("completed result identity or hash does not match");
            }
            if (errorCode != null || retryable || recoveryAction != null) {
                throw new IllegalArgumentException("completed result cannot contain an error");
            }
        } else {
            if (graphResult != null || resultHash != null) {
                throw new IllegalArgumentException("non-completed result cannot contain a graph result");
            }
            required(errorCode, "errorCode");
            required(recoveryAction, "recoveryAction");
            if (recoveryAction == AgentRunRecoveryAction.RETRY_SAME_COMMAND
                    || recoveryAction == AgentRunRecoveryAction.RECONCILE_TERMINAL) {
                throw new IllegalArgumentException(
                        "Activity-local recovery actions cannot escape in a result");
            }
            if (retryable
                    != (recoveryAction == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT)) {
                throw new IllegalArgumentException(
                        "retryable must agree with the closed recovery action");
            }
        }
        required(completedAt, "completedAt");
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
    }

    public enum Outcome {
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
