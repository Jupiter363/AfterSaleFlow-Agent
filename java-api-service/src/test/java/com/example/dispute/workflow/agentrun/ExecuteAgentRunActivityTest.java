package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunActivityContext;
import com.example.dispute.workflow.activity.agent.AgentRunActivityContextProvider;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivityImpl;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.failure.ApplicationFailure;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecuteAgentRunActivityTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void duplicateExecutionReusesTheSameLogicalRunAndAttempt() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult graphResult = graphResult();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        AgentRunActivityContext context = context(1);
        AgentRunLedger.Attempt attempt = runningAttempt(0, false);
        when(ledger.startNextAttempt(request.agentRunId(), request, NOW)).thenReturn(attempt);
        when(gateway.execute(eq(request), any(), any()))
                .thenAnswer(invocation -> {
                    AgentRunExecutionGateway.ProgressListener listener = invocation.getArgument(1);
                    listener.onProgress(new AgentRunProgress(2, true, false));
                    return new AgentRunExecutionGateway.Completion(graphResult, 7, true);
                });
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, () -> context);

        ExecuteAgentRunResult first = activity.execute(request);
        ExecuteAgentRunResult duplicate = activity.execute(request);

        assertThat(first).isEqualTo(duplicate);
        assertThat(first.outcome()).isEqualTo(ExecuteAgentRunResult.Outcome.COMPLETED);
        assertThat(first.resultHash()).isEqualTo(graphResult.outputHash());
        verify(ledger, times(2)).startNextAttempt(request.agentRunId(), request, NOW);
        verify(ledger, times(2)).recordResultReady(first);
        verify(ledger, never()).createOrLoad(any());
    }

    @Test
    void completionLossRetriesTheStableCommandAndAcceptsTheCachedHash() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult graphResult = graphResult();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        AgentRunActivityContext firstContext = context(1);
        AgentRunActivityContext secondContext = context(2);
        AgentRunActivityContextProvider contexts = mock(AgentRunActivityContextProvider.class);
        when(contexts.current()).thenReturn(firstContext, secondContext);
        when(ledger.startNextAttempt(request.agentRunId(), request, NOW))
                .thenReturn(runningAttempt(0, false), runningAttempt(3, true));
        List<ExecuteAgentRunRequest> gatewayRequests = new ArrayList<>();
        AtomicInteger invocation = new AtomicInteger();
        when(gateway.execute(eq(request), any(), any()))
                .thenAnswer(call -> {
                    gatewayRequests.add(call.getArgument(0));
                    AgentRunExecutionGateway.ProgressListener listener = call.getArgument(1);
                    if (invocation.getAndIncrement() == 0) {
                        listener.onProgress(new AgentRunProgress(3, true, false));
                        throw AgentRunExecutionException.retryable(
                                "AGENT_RESPONSE_LOST",
                                "response lost after command-ledger commit",
                                true,
                                3,
                                true,
                                null);
                    }
                    return new AgentRunExecutionGateway.Completion(graphResult, 7, true);
                });
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, contexts);

        assertThatThrownBy(() -> activity.execute(request))
                .isInstanceOfSatisfying(
                        ApplicationFailure.class,
                        failure -> {
                            assertThat(failure.getType())
                                    .isEqualTo(ExecuteAgentRunActivityImpl.RETRYABLE_FAILURE_TYPE);
                            assertThat(failure.getNextRetryDelay())
                                    .isPositive()
                                    .isLessThanOrEqualTo(Duration.ofSeconds(30));
                        });
        ExecuteAgentRunResult cached = activity.execute(request);

        assertThat(gatewayRequests).containsExactly(request, request);
        assertThat(gatewayRequests)
                .allSatisfy(replayed -> {
                    assertThat(replayed.command().commandId())
                            .isEqualTo(request.command().commandId());
                    assertThat(replayed.attemptId()).isEqualTo(request.attemptId());
                    assertThat(replayed.attemptNo()).isEqualTo(request.attemptNo());
                });
        assertThat(cached.resultHash()).isEqualTo(graphResult.outputHash());
        verify(ledger, never())
                .recordAttemptFailure(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        any());
    }

    @Test
    void visibleOutputFailureWithoutReplayGuaranteeAbortsInsteadOfAutoRetrying()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.startNextAttempt(request.agentRunId(), request, NOW))
                .thenReturn(runningAttempt(0, false));
        when(gateway.execute(eq(request), any(), any()))
                .thenAnswer(call -> {
                    AgentRunExecutionGateway.ProgressListener listener = call.getArgument(1);
                    listener.onProgress(new AgentRunProgress(4, true, false));
                    throw AgentRunExecutionException.retryable(
                            "AGENT_STREAM_INTERRUPTED",
                            "stream interrupted before durable completion",
                            false,
                            4,
                            true,
                            null);
                });
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, () -> context(1));

        ExecuteAgentRunResult result = activity.execute(request);

        assertThat(result.outcome()).isEqualTo(ExecuteAgentRunResult.Outcome.FAILED);
        assertThat(result.publicOutputEmitted()).isTrue();
        assertThat(result.retryable()).isFalse();
        verify(ledger).recordAttemptFailure(
                request.agentRunId(),
                request.attemptId(),
                request.attemptNo(),
                AgentRunAttemptStatus.ABORTED,
                "AGENT_STREAM_INTERRUPTED",
                false,
                NOW);
    }

    private static ExecuteAgentRunActivityImpl activity(
            AgentRunLedger ledger,
            AgentRunExecutionGateway gateway,
            AgentRunActivityContextProvider contexts) {
        return new ExecuteAgentRunActivityImpl(
                ledger,
                gateway,
                contexts,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(1),
                Executors::newSingleThreadScheduledExecutor);
    }

    private static AgentRunActivityContext context(int temporalAttempt) {
        return new AgentRunActivityContext() {
            @Override
            public int temporalAttempt() {
                return temporalAttempt;
            }

            @Override
            public void heartbeat(
                    com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat details) {}
        };
    }

    private static AgentRunLedger.Attempt runningAttempt(
            long lastSequenceNo,
            boolean publicOutputEmitted) {
        return new AgentRunLedger.Attempt(
                "attempt-001",
                "agent-run-001",
                1,
                AgentRunAttemptStatus.RUNNING,
                publicOutputEmitted,
                lastSequenceNo,
                null,
                NOW,
                null,
                0);
    }

    private static ExecuteAgentRunRequest request() throws Exception {
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                "agent-run-001",
                1,
                "agent-stream.v2",
                fixture("room-graph-command-valid.json", RoomGraphCommand.class));
    }

    private static RoomGraphResult graphResult() throws Exception {
        return fixture("room-graph-result-valid.json", RoomGraphResult.class);
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
