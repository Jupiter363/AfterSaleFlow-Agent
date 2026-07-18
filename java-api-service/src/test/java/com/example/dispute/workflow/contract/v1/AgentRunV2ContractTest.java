package com.example.dispute.workflow.contract.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.config.AgentRunV2Properties;
import com.example.dispute.workflow.config.AgentRunV2Properties.SchedulerMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunV2ContractTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");

    @Test
    void executionEnvelopeReusesTheFrozenGraphIdentity() throws Exception {
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                "agent-run-001",
                1,
                "agent-stream.v2",
                command);

        assertThat(request.logicalRunId()).isEqualTo(command.logicalRunId());
        assertThat(request.attemptId()).isEqualTo(command.attemptId());
    }

    @Test
    void completedResultAndFinalizationReceiptAreHashBound() throws Exception {
        RoomGraphResult graphResult = fixture("room-graph-result-valid.json", RoomGraphResult.class);
        ExecuteAgentRunResult result = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                "agent-run-001",
                graphResult.logicalRunId(),
                graphResult.attemptId(),
                1,
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                graphResult.outputHash(),
                7,
                true,
                null,
                false,
                Instant.parse("2026-07-19T00:00:00Z"));
        AgentRunFinalizationReceipt receipt = new AgentRunFinalizationReceipt(
                AgentRunFinalizationReceipt.SCHEMA_VERSION,
                result.agentRunId(),
                result.logicalRunId(),
                result.attemptId(),
                result.attemptNo(),
                9,
                result.resultHash(),
                "manifest-001",
                "b".repeat(64),
                8,
                AgentRunFinalizationReceipt.CommitStatus.COMMITTED,
                Instant.parse("2026-07-19T00:00:01Z"));

        assertThat(receipt.finalResultHash()).isEqualTo(graphResult.outputHash());
        assertThatThrownBy(() -> new ExecuteAgentRunResult(
                        ExecuteAgentRunResult.SCHEMA_VERSION,
                        "agent-run-001",
                        graphResult.logicalRunId(),
                        graphResult.attemptId(),
                        1,
                        ExecuteAgentRunResult.Outcome.COMPLETED,
                        graphResult,
                        "c".repeat(64),
                        7,
                        true,
                        null,
                        false,
                        Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity or hash");
    }

    @Test
    void configurationRejectsLegacyExecutionOfV2() {
        AgentRunV2Properties defaults = new AgentRunV2Properties(
                false,
                AgentRunProtocol.V1,
                SchedulerMode.EXECUTOR,
                Duration.ofMinutes(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5));
        assertThat(defaults.enabled()).isFalse();

        assertThatThrownBy(() -> new AgentRunV2Properties(
                        true,
                        AgentRunProtocol.V2,
                        SchedulerMode.EXECUTOR,
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot execute V2");
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
