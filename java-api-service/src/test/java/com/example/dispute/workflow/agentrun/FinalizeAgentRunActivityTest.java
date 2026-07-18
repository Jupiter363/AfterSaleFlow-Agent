package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.activity.agent.FinalizeAgentRunActivityImpl;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunTemporalPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.failure.ApplicationFailure;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FinalizeAgentRunActivityTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:05:00Z");

    @Test
    void returnsCommittedOrReplayReceiptFromTheDomainGateway() throws Exception {
        ExecuteAgentRunRequest request = request();
        ExecuteAgentRunResult result = result(request);
        AgentRunFinalizationReceipt receipt = receipt(request, result, CommitStatus.ALREADY_COMMITTED);
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result)).thenReturn(receipt);

        var activity = new FinalizeAgentRunActivityImpl(gateway);

        assertThat(activity.finalizeResult(request, result)).isEqualTo(receipt);
        verify(gateway).finalizeResult(request, result);
    }

    @Test
    void deterministicFenceRejectionIsNonRetryable() throws Exception {
        ExecuteAgentRunRequest request = request();
        ExecuteAgentRunResult result = result(request);
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result))
                .thenThrow(new IllegalStateException("stale room fence"));

        assertThatThrownBy(() ->
                        new FinalizeAgentRunActivityImpl(gateway).finalizeResult(request, result))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> {
                    ApplicationFailure applicationFailure = (ApplicationFailure) failure;
                    assertThat(applicationFailure.getType())
                            .isEqualTo(FinalizeAgentRunActivityImpl.NON_RETRYABLE_FAILURE_TYPE);
                    assertThat(applicationFailure.isNonRetryable()).isTrue();
                });
    }

    @Test
    void infrastructureFailureEscapesForTemporalFinalizerOnlyRetry() throws Exception {
        ExecuteAgentRunRequest request = request();
        ExecuteAgentRunResult result = result(request);
        RuntimeException databaseFailure = new RuntimeException("database unavailable");
        AgentRunFinalizationGateway gateway = mock(AgentRunFinalizationGateway.class);
        when(gateway.finalizeResult(request, result)).thenThrow(databaseFailure);

        assertThatThrownBy(() ->
                        new FinalizeAgentRunActivityImpl(gateway).finalizeResult(request, result))
                .isSameAs(databaseFailure);
    }

    @Test
    void finalizerPolicyRetriesIndependentlyWithoutAnExecutionAttemptLimit() {
        var options = AgentRunTemporalPolicy.finalizerActivityOptions();

        assertThat(options.getStartToCloseTimeout())
                .isEqualTo(AgentRunTemporalPolicy.FINALIZER_START_TO_CLOSE_TIMEOUT);
        assertThat(options.getRetryOptions().getMaximumAttempts()).isZero();
        assertThat(options.getRetryOptions().getDoNotRetry())
                .containsExactly(FinalizeAgentRunActivityImpl.NON_RETRYABLE_FAILURE_TYPE);
    }

    private static ExecuteAgentRunRequest request() throws Exception {
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                1,
                "agent-stream.v2",
                command);
    }

    private static ExecuteAgentRunResult result(ExecuteAgentRunRequest request) throws Exception {
        RoomGraphResult graphResult = fixture("room-graph-result-valid.json", RoomGraphResult.class);
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                graphResult.outputHash(),
                2,
                true,
                null,
                false,
                NOW);
    }

    private static AgentRunFinalizationReceipt receipt(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            CommitStatus status) {
        return new AgentRunFinalizationReceipt(
                AgentRunFinalizationReceipt.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                7,
                result.resultHash(),
                "manifest-001",
                "a".repeat(64),
                result.lastSequenceNo(),
                status,
                NOW);
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
