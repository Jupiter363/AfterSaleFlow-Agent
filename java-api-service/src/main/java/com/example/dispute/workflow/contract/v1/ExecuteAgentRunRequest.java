package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExecuteAgentRunRequest(
        String schemaVersion,
        String agentRunId,
        long attemptNo,
        int attemptLimit,
        String streamProtocol,
        String logicalInputHash,
        String previousAttemptId,
        boolean resetRequired,
        int publicSequenceOffset,
        RoomGraphCommand command) {

    public static final String SCHEMA_VERSION = "execute-agent-run.v3";
    public static final int MAXIMUM_ATTEMPT_LIMIT = 3;

    public ExecuteAgentRunRequest(
            String schemaVersion,
            String agentRunId,
            long attemptNo,
            String streamProtocol,
            String logicalInputHash,
            String previousAttemptId,
            boolean resetRequired,
            int publicSequenceOffset,
            RoomGraphCommand command) {
        this(
                schemaVersion,
                agentRunId,
                attemptNo,
                MAXIMUM_ATTEMPT_LIMIT,
                streamProtocol,
                logicalInputHash,
                previousAttemptId,
                resetRequired,
                publicSequenceOffset,
                command);
    }

    public ExecuteAgentRunRequest {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        required(agentRunId, "agentRunId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        if (attemptLimit < 1 || attemptLimit > MAXIMUM_ATTEMPT_LIMIT) {
            throw new IllegalArgumentException("attemptLimit must be between 1 and 3");
        }
        if (attemptNo > attemptLimit) {
            throw new IllegalArgumentException("attemptNo exceeds attemptLimit");
        }
        if (!"agent-stream.v3".equals(streamProtocol)) {
            throw new IllegalArgumentException("streamProtocol must be agent-stream.v3");
        }
        required(logicalInputHash, "logicalInputHash");
        if (!logicalInputHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "logicalInputHash must be a lowercase SHA-256");
        }
        required(command, "command");
        if (!agentRunId.equals(command.logicalRunId())) {
            throw new IllegalArgumentException(
                    "agentRunId must equal the graph logicalRunId");
        }
        if (attemptNo == 1) {
            if (previousAttemptId != null || resetRequired || publicSequenceOffset != 0) {
                throw new IllegalArgumentException(
                        "attempt one cannot carry predecessor or reset lineage");
            }
        } else {
            required(previousAttemptId, "previousAttemptId");
            if (previousAttemptId.equals(command.attemptId())) {
                throw new IllegalArgumentException(
                        "previousAttemptId cannot equal the current attemptId");
            }
        }
        int expectedOffset = resetRequired ? 1 : 0;
        if (publicSequenceOffset != expectedOffset) {
            throw new IllegalArgumentException(
                    "publicSequenceOffset must be derived from resetRequired");
        }
    }

    public String logicalRunId() {
        return command.logicalRunId();
    }

    public String attemptId() {
        return command.attemptId();
    }
}
