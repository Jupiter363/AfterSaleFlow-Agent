package com.example.dispute.workflow.contract.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.config.AgentRunV2Properties;
import com.example.dispute.workflow.config.AgentRunV2Properties.SchedulerMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
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
                command.logicalRunId(),
                1,
                "agent-stream.v2",
                "b".repeat(64),
                null,
                false,
                0,
                command);

        assertThat(request.logicalRunId()).isEqualTo(command.logicalRunId());
        assertThat(request.attemptId()).isEqualTo(command.attemptId());
        assertThat(request.schemaVersion()).isEqualTo("execute-agent-run.v3");
        assertThat(request.logicalInputHash()).isEqualTo("b".repeat(64));
        assertThat(request.attemptLimit()).isEqualTo(3);
        assertThat(MAPPER.valueToTree(request).required("attempt_limit").asInt())
                .isEqualTo(3);
    }

    @Test
    void executionEnvelopeRequiresProofCarryingAttemptLineage() throws Exception {
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);

        assertThatThrownBy(() -> new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        command.logicalRunId(),
                        2,
                        "agent-stream.v2",
                        "b".repeat(64),
                        null,
                        false,
                        0,
                        command))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("previousAttemptId");
        assertThatThrownBy(() -> new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        command.logicalRunId(),
                        2,
                        "agent-stream.v2",
                        "b".repeat(64),
                        "attempt-previous-001",
                        true,
                        0,
                        command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicSequenceOffset");
    }

    @Test
    void executionEnvelopeBindsTheConfiguredLogicalAttemptLimit() throws Exception {
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);

        assertThatThrownBy(() -> new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        command.logicalRunId(),
                        2,
                        1,
                        "agent-stream.v2",
                        "b".repeat(64),
                        "attempt-previous-001",
                        false,
                        0,
                        command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptNo exceeds attemptLimit");
        assertThatThrownBy(() -> new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        command.logicalRunId(),
                        1,
                        4,
                        "agent-stream.v2",
                        "b".repeat(64),
                        null,
                        false,
                        0,
                        command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptLimit");
    }

    @Test
    void completedResultAndFinalizationReceiptAreHashBound() throws Exception {
        RoomGraphResult graphResult = fixture("room-graph-result-valid.json", RoomGraphResult.class);
        ExecuteAgentRunResult result = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                graphResult.logicalRunId(),
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
                null,
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
                        graphResult.logicalRunId(),
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
                        null,
                        Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity or hash");
    }

    @Test
    void executionEnvelopeRejectsAnIndependentAgentRunIdentity() throws Exception {
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);

        assertThatThrownBy(
                        () ->
                                new ExecuteAgentRunRequest(
                                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                                        "different-run",
                                        1,
                                        "agent-stream.v2",
                                        "b".repeat(64),
                                        null,
                                        false,
                                        0,
                                        command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logicalRunId");
    }

    @Test
    void failedResultRequiresAClosedRecoveryActionConsistentWithRetryable() {
        ExecuteAgentRunResult retryable = failedResult(
                true, AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);

        assertThat(retryable.schemaVersion()).isEqualTo("execute-agent-run-result.v3");
        assertThat(retryable.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);
        assertThatThrownBy(() -> failedResult(true, AgentRunRecoveryAction.FAIL_LOGICAL_RUN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closed recovery action");
        assertThatThrownBy(() -> failedResult(false, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("recoveryAction");
        assertThatThrownBy(
                        () -> failedResult(false, AgentRunRecoveryAction.RETRY_SAME_COMMAND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Activity-local");
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
                .hasMessageContaining("cannot use the legacy scheduler EXECUTOR");
    }

    private static ExecuteAgentRunResult failedResult(
            boolean retryable, AgentRunRecoveryAction recoveryAction) {
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                "run-001",
                "run-001",
                "attempt-001",
                1,
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                0,
                false,
                "GRAPH_RECOVERY_REQUIRED",
                retryable,
                recoveryAction,
                Instant.parse("2026-07-19T00:00:00Z"));
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
