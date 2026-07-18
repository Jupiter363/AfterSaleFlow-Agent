package com.example.dispute.workflow.agentrun;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunActivityContext;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunHeartbeatMonitor;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.temporal.agentrun.AgentRunTemporalPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ExecuteAgentRunActivityHeartbeatTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void meaningfulProgressProducesMonotonicPublicOnlyHeartbeats() throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        List<AgentRunAttemptHeartbeat> temporalHeartbeats = new ArrayList<>();
        AgentRunActivityContext context = new AgentRunActivityContext() {
            @Override
            public int temporalAttempt() {
                return 1;
            }

            @Override
            public void heartbeat(AgentRunAttemptHeartbeat details) {
                temporalHeartbeats.add(details);
            }
        };
        AgentRunHeartbeatMonitor monitor = new AgentRunHeartbeatMonitor(
                request,
                runningAttempt(),
                ledger,
                context,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(1),
                new AgentRunCancellationToken(),
                Executors.newSingleThreadScheduledExecutor());

        try (monitor) {
            monitor.start();
            monitor.progress(new AgentRunProgress(4, true, false));
            monitor.progress(new AgentRunProgress(7, false, true));
        }

        assertThat(temporalHeartbeats).hasSize(3);
        assertThat(temporalHeartbeats)
                .extracting(AgentRunAttemptHeartbeat::lastSequenceNo)
                .containsExactly(0L, 4L, 7L);
        AgentRunAttemptHeartbeat terminal = temporalHeartbeats.getLast();
        assertThat(terminal.agentRunId()).isEqualTo(request.agentRunId());
        assertThat(terminal.attemptId()).isEqualTo(request.attemptId());
        assertThat(terminal.publicOutputEmitted()).isTrue();
        assertThat(terminal.finalFrameObserved()).isTrue();
        assertThat(terminal.recordedAt()).isEqualTo(NOW);
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.times(3)).recordHeartbeat(any());
    }

    @Test
    void regressingSequenceFailsClosedBeforeItCanReplaceDurableProgress() throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunHeartbeatMonitor monitor = new AgentRunHeartbeatMonitor(
                request,
                runningAttempt(),
                ledger,
                noOpContext(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(1),
                new AgentRunCancellationToken(),
                Executors.newSingleThreadScheduledExecutor());

        try (monitor) {
            monitor.start();
            monitor.progress(new AgentRunProgress(5, false, false));
            assertThatThrownBy(() -> monitor.progress(new AgentRunProgress(4, false, false)))
                    .isInstanceOf(AgentRunExecutionException.class)
                    .hasMessageContaining("regressed");
        }
    }

    @Test
    void workflowPolicyUsesTheContractFixedTimeoutsAndBoundedRetry() {
        ActivityOptions options = AgentRunTemporalPolicy.activityOptions();

        assertThat(options.getTaskQueue()).isEqualTo(AGENT_EXECUTION);
        assertThat(options.getStartToCloseTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(options.getHeartbeatTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(AgentRunTemporalPolicy.PROGRESS_HEARTBEAT_INTERVAL)
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(options.getRetryOptions().getMaximumAttempts()).isEqualTo(3);
        assertThat(options.getCancellationType())
                .isEqualTo(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
        assertThat(AgentRunTemporalPolicy.activityOptions(2)
                        .getRetryOptions()
                        .getMaximumAttempts())
                .isEqualTo(2);
        assertThat(AgentRunTemporalPolicy.activityOptions(0)
                        .getRetryOptions()
                        .getMaximumAttempts())
                .isEqualTo(1);
    }

    private static AgentRunActivityContext noOpContext() {
        return new AgentRunActivityContext() {
            @Override
            public int temporalAttempt() {
                return 1;
            }

            @Override
            public void heartbeat(AgentRunAttemptHeartbeat details) {}
        };
    }

    private static AgentRunLedger.Attempt runningAttempt() {
        return new AgentRunLedger.Attempt(
                "attempt-001",
                "agent-run-001",
                1,
                AgentRunAttemptStatus.RUNNING,
                false,
                0,
                null,
                NOW,
                null,
                0);
    }

    private static ExecuteAgentRunRequest request() throws Exception {
        Path fixture = Path.of(
                "..",
                "contracts",
                "agent-platform",
                "v1",
                "fixtures",
                "valid",
                "room-graph-command-valid.json");
        JsonNode wrapper = MAPPER.readTree(fixture.toFile());
        RoomGraphCommand command =
                MAPPER.treeToValue(wrapper.required("instance"), RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                "agent-run-001",
                1,
                "agent-stream.v2",
                command);
    }
}
