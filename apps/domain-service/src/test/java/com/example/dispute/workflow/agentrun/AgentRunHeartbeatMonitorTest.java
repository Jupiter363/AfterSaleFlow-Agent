package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunActivityContext;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunHeartbeatMonitor;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
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
import org.junit.jupiter.api.Test;

class AgentRunHeartbeatMonitorTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void durableFinalAdvancesOnlyTheLocalSnapshotAfterCancellationRacesCommit()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunCancellationToken token = mock(AgentRunCancellationToken.class);
        AtomicBoolean finalCommitted = new AtomicBoolean();
        doAnswer(invocation -> {
                    if (finalCommitted.get()) {
                        throw new ActivityCanceledException();
                    }
                    return null;
                })
                .when(token)
                .throwIfCancellationRequested();
        AgentRunActivityContext context = new AgentRunActivityContext() {
            @Override
            public int temporalAttempt() {
                return 1;
            }

            @Override
            public void heartbeat(AgentRunAttemptHeartbeat details) {}
        };
        AgentRunHeartbeatMonitor monitor = new AgentRunHeartbeatMonitor(
                request,
                runningAttempt(),
                ledger,
                context,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(1),
                token,
                Executors.newSingleThreadScheduledExecutor());

        try (monitor) {
            monitor.start();
            finalCommitted.set(true);
            monitor.durableFinal(new AgentRunProgress(7, true, true));

            assertThat(monitor.snapshot()).isEqualTo(new AgentRunProgress(7, true, true));
        }

        verify(token, times(1)).throwIfCancellationRequested();
        verify(ledger, times(1)).recordHeartbeat(
                org.mockito.ArgumentMatchers.any(AgentRunAttemptHeartbeat.class));
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

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
