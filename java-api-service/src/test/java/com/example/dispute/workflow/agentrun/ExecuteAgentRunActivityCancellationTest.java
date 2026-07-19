package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunActivityContext;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivityImpl;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.client.ActivityCanceledException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecuteAgentRunActivityCancellationTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void cancellationBeforeDurableFinalClosesTheStreamAndCancelsTheAttempt() throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request))
                .thenReturn(runningAttempt());
        AtomicInteger heartbeatCount = new AtomicInteger();
        AgentRunActivityContext context = new AgentRunActivityContext() {
            @Override
            public int temporalAttempt() {
                return 1;
            }

            @Override
            public void heartbeat(AgentRunAttemptHeartbeat details) {
                if (heartbeatCount.incrementAndGet() == 2) {
                    throw new ActivityCanceledException();
                }
            }
        };
        AtomicBoolean streamClosed = new AtomicBoolean();
        when(gateway.execute(
                        eq(request),
                        eq(ExecutionMode.EXECUTE_OR_RECONCILE),
                        any(),
                        any()))
                .thenAnswer(invocation -> {
                    AgentRunExecutionGateway.ProgressListener listener = invocation.getArgument(2);
                    com.example.dispute.workflow.activity.agent.AgentRunCancellationToken token =
                            invocation.getArgument(3);
                    token.onCancellation(() -> streamClosed.set(true));
                    listener.onProgress(new AgentRunProgress(1, true, false));
                    throw new AssertionError("cancellation must stop execution before final");
                });
        ExecuteAgentRunActivityImpl activity = new ExecuteAgentRunActivityImpl(
                ledger,
                gateway,
                () -> context,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(1),
                Executors::newSingleThreadScheduledExecutor);

        assertThatThrownBy(() -> activity.execute(request))
                .isInstanceOf(ActivityCanceledException.class);

        assertThat(streamClosed).isTrue();
        verify(ledger).recordAttemptFailure(
                request.agentRunId(),
                request.attemptId(),
                request.attemptNo(),
                AgentRunAttemptStatus.CANCELLED,
                "AGENT_RUN_CANCELLED",
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                NOW);
        verify(ledger, never()).recordResultReady(any());
    }

    @Test
    void cancellationAfterDurableGatewayCompletionCannotReverseResultReady() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult graphResult = graphResult();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request)).thenReturn(runningAttempt());
        AtomicInteger heartbeatCount = new AtomicInteger();
        AgentRunActivityContext context = new AgentRunActivityContext() {
            @Override
            public int temporalAttempt() {
                return 1;
            }

            @Override
            public void heartbeat(AgentRunAttemptHeartbeat details) {
                if (heartbeatCount.incrementAndGet() == 2) {
                    throw new ActivityCanceledException();
                }
            }
        };
        AtomicBoolean streamClosed = new AtomicBoolean();
        when(gateway.execute(
                        eq(request),
                        eq(ExecutionMode.EXECUTE_OR_RECONCILE),
                        any(),
                        any()))
                .thenAnswer(invocation -> {
                    AgentRunExecutionGateway.ProgressListener listener = invocation.getArgument(2);
                    com.example.dispute.workflow.activity.agent.AgentRunCancellationToken token =
                            invocation.getArgument(3);
                    token.onCancellation(() -> streamClosed.set(true));
                    // The gateway contract says this completion is already durable. The callback
                    // only makes the concurrent cancellation visible to the Activity.
                    try {
                        listener.onProgress(new AgentRunProgress(7, true, false));
                    } catch (ActivityCanceledException ignored) {
                        // Cancellation raced the durable-final return.
                    }
                    return new AgentRunExecutionGateway.Completion(graphResult, 7, true);
                });
        ExecuteAgentRunActivityImpl activity = new ExecuteAgentRunActivityImpl(
                ledger,
                gateway,
                () -> context,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(1),
                Executors::newSingleThreadScheduledExecutor);

        var result = activity.execute(request);

        assertThat(result.outcome()).isEqualTo(ExecuteAgentRunResult.Outcome.COMPLETED);
        assertThat(result.lastSequenceNo()).isEqualTo(7);
        assertThat(result.publicOutputEmitted()).isTrue();
        assertThat(streamClosed).isTrue();
        verify(ledger).recordResultReady(result);
        verify(ledger, never()).recordAttemptFailure(
                any(), any(), anyLong(), any(), any(), any(), any());
    }

    private static AgentRunLedger.Attempt runningAttempt() {
        return new AgentRunLedger.Attempt(
                "attempt-001",
                "run-001",
                1,
                AgentRunAttemptStatus.RUNNING,
                false,
                false,
                0,
                null,
                NOW,
                null,
                0,
                "agent-run-attempt-lineage.v1",
                "graph-cmd-001",
                "78aa57b57feda88e27adf9bc1b2cacd6aa3c2deb4281fb89533e9f8fb774e430",
                "b".repeat(64),
                "{}",
                null,
                false,
                0,
                null);
    }

    private static ExecuteAgentRunRequest request() throws Exception {
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                "run-001",
                1,
                "agent-stream.v2",
                "b".repeat(64),
                null,
                false,
                0,
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
