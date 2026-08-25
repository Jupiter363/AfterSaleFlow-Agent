package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentRunAttemptHeartbeat(
        String schemaVersion,
        String agentRunId,
        String attemptId,
        long attemptNo,
        long lastSequenceNo,
        boolean publicOutputEmitted,
        boolean finalFrameObserved,
        Instant recordedAt) {

    public static final String SCHEMA_VERSION = "agent-run-attempt-heartbeat.v2";

    public AgentRunAttemptHeartbeat {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        required(agentRunId, "agentRunId");
        required(attemptId, "attemptId");
        if (attemptNo < 1 || lastSequenceNo < -1) {
            throw new IllegalArgumentException("attemptNo and lastSequenceNo are invalid");
        }
        if (lastSequenceNo == -1 && (publicOutputEmitted || finalFrameObserved)) {
            throw new IllegalArgumentException(
                    "empty stream baseline cannot carry public or final progress");
        }
        required(recordedAt, "recordedAt");
    }
}
