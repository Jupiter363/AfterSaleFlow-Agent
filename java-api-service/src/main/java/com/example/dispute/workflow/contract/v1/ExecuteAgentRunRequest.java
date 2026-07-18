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
        String streamProtocol,
        RoomGraphCommand command) {

    public static final String SCHEMA_VERSION = "execute-agent-run.v2";

    public ExecuteAgentRunRequest {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        required(agentRunId, "agentRunId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        if (!"agent-stream.v2".equals(streamProtocol)) {
            throw new IllegalArgumentException("streamProtocol must be agent-stream.v2");
        }
        required(command, "command");
    }

    public String logicalRunId() {
        return command.logicalRunId();
    }

    public String attemptId() {
        return command.attemptId();
    }
}
