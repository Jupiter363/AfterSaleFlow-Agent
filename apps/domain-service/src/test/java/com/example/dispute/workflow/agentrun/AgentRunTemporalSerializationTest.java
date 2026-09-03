package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunTemporalSerializationTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final DataConverter TEMPORAL_CONVERTER = DefaultDataConverter.STANDARD_INSTANCE;
    private static final Path FIXTURES =
            Path.of("..", "..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void defaultTemporalConverterRoundTripsExecutionAndHeartbeatContracts() throws Exception {
        RoomGraphCommand command =
                fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                1,
                "agent-stream.v2",
                "b".repeat(64),
                null,
                false,
                0,
                command);
        RoomGraphResult graphResult =
                fixture("room-graph-result-valid.json", RoomGraphResult.class);
        ExecuteAgentRunResult result = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                graphResult.outputHash(),
                7,
                true,
                null,
                false,
                null,
                NOW);
        AgentRunAttemptHeartbeat heartbeat = new AgentRunAttemptHeartbeat(
                AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                request.agentRunId(),
                request.attemptId(),
                request.attemptNo(),
                7,
                true,
                true,
                NOW);
        ExecuteAgentRunResult retryableFailure = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                7,
                true,
                "GRAPH_NEW_AGENT_ATTEMPT_REQUIRED",
                true,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                NOW);

        assertThat(roundTrip(request, ExecuteAgentRunRequest.class)).isEqualTo(request);
        assertThat(roundTrip(result, ExecuteAgentRunResult.class)).isEqualTo(result);
        assertThat(roundTrip(retryableFailure, ExecuteAgentRunResult.class))
                .isEqualTo(retryableFailure);
        assertThat(roundTrip(heartbeat, AgentRunAttemptHeartbeat.class)).isEqualTo(heartbeat);
    }

    private static <T> T roundTrip(T value, Class<T> type) {
        return TEMPORAL_CONVERTER.fromPayloads(
                0,
                TEMPORAL_CONVERTER.toPayloads(value),
                type,
                type);
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
