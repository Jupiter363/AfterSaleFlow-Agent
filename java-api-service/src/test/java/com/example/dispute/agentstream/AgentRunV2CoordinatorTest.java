package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.agentstream.application.AgentRunV2Coordinator;
import com.example.dispute.agentstream.application.AgentRunV2Coordinator.Selection;
import com.example.dispute.agentstream.application.AgentRunV2Coordinator.StartCommand;
import com.example.dispute.workflow.application.AgentRunV2WorkflowLaunchException;
import com.example.dispute.workflow.application.AgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.application.AgentRunV2WorkflowLauncher.StartDisposition;
import com.example.dispute.workflow.application.AgentRunV2WorkflowLauncher.StartReceipt;
import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.config.AgentRunV2Properties;
import com.example.dispute.workflow.config.AgentRunV2Properties.SchedulerMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

@ExtendWith(MockitoExtension.class)
class AgentRunV2CoordinatorTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURE =
            Path.of(
                    "..",
                    "contracts",
                    "agent-platform",
                    "v1",
                    "fixtures",
                    "valid",
                    "room-graph-command-valid.json");
    private static final Instant NOW = Instant.parse("2026-07-19T06:00:00Z");

    @Mock private AgentRunLedger ledger;
    @Mock private AgentRunV2WorkflowLauncher launcher;

    private RoomGraphCommand graphCommand;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURE.toFile());
        graphCommand = MAPPER.treeToValue(wrapper.required("instance"), RoomGraphCommand.class);
    }

    @Test
    void reusesLogicalIdentityAndHandsTheMonotonicAttemptToTheStableWorkflow() {
        StartCommand command = command(Selection.SHADOW, 1);
        LogicalRun logical = logicalRun();
        Attempt attempt = attempt(AgentRunAttemptStatus.RUNNING);
        when(ledger.createOrLoad(any())).thenReturn(logical);
        when(ledger.startNextAttempt(any(), any(), any())).thenReturn(attempt);
        String workflowId = TemporalAgentRunV2WorkflowLauncher.workflowId(logical.agentRunId());
        when(launcher.start(any()))
                .thenReturn(
                        new StartReceipt(workflowId, "temporal-run-001", StartDisposition.STARTED));

        AgentRunV2Coordinator.StartOutcome outcome = coordinator(enabled()).start(command);

        ArgumentCaptor<CreateLogicalRun> logicalCaptor =
                ArgumentCaptor.forClass(CreateLogicalRun.class);
        verify(ledger).createOrLoad(logicalCaptor.capture());
        assertThat(logicalCaptor.getValue().agentRunId()).isEqualTo(graphCommand.logicalRunId());
        assertThat(logicalCaptor.getValue().protocol()).isEqualTo(AgentRunProtocol.V2);
        assertThat(logicalCaptor.getValue().executorKind())
                .isEqualTo(AgentRunExecutorKind.TEMPORAL_ACTIVITY);

        ArgumentCaptor<ExecuteAgentRunRequest> requestCaptor =
                ArgumentCaptor.forClass(ExecuteAgentRunRequest.class);
        verify(ledger)
                .startNextAttempt(
                        org.mockito.ArgumentMatchers.eq(logical.agentRunId()),
                        requestCaptor.capture(),
                        org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(requestCaptor.getValue().attemptNo()).isEqualTo(1);
        assertThat(requestCaptor.getValue().attemptId()).isEqualTo(graphCommand.attemptId());
        assertThat(outcome.workflow().workflowId()).isEqualTo(workflowId);
        assertThat(outcome.request()).isEqualTo(requestCaptor.getValue());
    }

    @Test
    void acceptsAnExactCompletedReplayWithoutCreatingAnotherWorkflowIdentity() {
        when(ledger.createOrLoad(any())).thenReturn(logicalRun());
        when(ledger.startNextAttempt(any(), any(), any()))
                .thenReturn(attempt(AgentRunAttemptStatus.COMPLETED));
        String workflowId =
                TemporalAgentRunV2WorkflowLauncher.workflowId(graphCommand.logicalRunId());
        when(launcher.start(any()))
                .thenReturn(
                        new StartReceipt(
                                workflowId, "temporal-run-001", StartDisposition.ALREADY_STARTED));

        AgentRunV2Coordinator.StartOutcome replay =
                coordinator(enabled()).start(command(Selection.SHADOW, 1));

        assertThat(replay.workflow().disposition()).isEqualTo(StartDisposition.ALREADY_STARTED);
        assertThat(replay.workflow().workflowId()).isEqualTo(workflowId);
    }

    @Test
    void rejectsAWorkflowReceiptOutsideTheLogicalRunIdentity() {
        when(ledger.createOrLoad(any())).thenReturn(logicalRun());
        when(ledger.startNextAttempt(any(), any(), any()))
                .thenReturn(attempt(AgentRunAttemptStatus.RUNNING));
        when(launcher.start(any()))
                .thenReturn(
                        new StartReceipt(
                                "agent-run-v2:another-logical-run",
                                "temporal-run-001",
                                StartDisposition.STARTED));

        assertThatThrownBy(() -> coordinator(enabled()).start(command(Selection.SHADOW, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow receipt");
    }

    @Test
    void closesAStillRunningAttemptAfterPermanentTemporalAdmissionRejection() {
        when(ledger.createOrLoad(any())).thenReturn(logicalRun());
        when(ledger.startNextAttempt(any(), any(), any()))
                .thenReturn(attempt(AgentRunAttemptStatus.RUNNING));
        AgentRunV2WorkflowLaunchException rejected =
                AgentRunV2WorkflowLaunchException.permanent(
                        "TEMPORAL_UPDATE_REJECTED",
                        new IllegalArgumentException("validator rejected the attempt"));
        when(launcher.start(any())).thenThrow(rejected);

        assertThatThrownBy(() -> coordinator(enabled()).start(command(Selection.SHADOW, 1)))
                .isSameAs(rejected);
        verify(ledger)
                .recordAttemptFailure(
                        graphCommand.logicalRunId(),
                        graphCommand.attemptId(),
                        1,
                        AgentRunAttemptStatus.FAILED,
                        "TEMPORAL_UPDATE_REJECTED",
                        false,
                        NOW);
    }

    @Test
    void preservesTheDurableAttemptWhenTemporalDispatchCanBeRetried() {
        when(ledger.createOrLoad(any())).thenReturn(logicalRun());
        when(ledger.startNextAttempt(any(), any(), any()))
                .thenReturn(attempt(AgentRunAttemptStatus.RUNNING));
        AgentRunV2WorkflowLaunchException unavailable =
                AgentRunV2WorkflowLaunchException.retryable(
                        "TEMPORAL_DISPATCH_FAILED",
                        new IllegalStateException("Temporal is unavailable"));
        when(launcher.start(any())).thenThrow(unavailable);

        assertThatThrownBy(() -> coordinator(enabled()).start(command(Selection.SHADOW, 1)))
                .isSameAs(unavailable);
        verify(ledger, never())
                .recordAttemptFailure(any(), any(), eq(1L), any(), any(), eq(false), any());
    }

    @Test
    void failsClosedWhenV2IsOffOrTheLegacySchedulerWouldExecuteIt() {
        assertThatThrownBy(() -> coordinator(disabled()).start(command(Selection.SHADOW, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OFF");

        assertThatThrownBy(() -> coordinator(executorMode()).start(command(Selection.SHADOW, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy scheduler");

        assertThatThrownBy(() -> coordinator(enabled()).start(command(Selection.OFF, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHADOW");
    }

    private AgentRunV2Coordinator coordinator(AgentRunV2Properties properties) {
        return new AgentRunV2Coordinator(
                ledger, launcher, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StartCommand command(Selection selection, int attemptNo) {
        return new StartCommand(
                selection,
                "logical-key-001",
                "ROOM_EVIDENCE_001",
                "EPOCH_EVIDENCE_001",
                "EVIDENCE_ANALYZE",
                7,
                attemptNo,
                3,
                graphCommand);
    }

    private LogicalRun logicalRun() {
        return new LogicalRun(
                graphCommand.logicalRunId(),
                graphCommand.caseId(),
                "logical-key-001",
                AgentRunProtocol.V2,
                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                "EPOCH_EVIDENCE_001",
                graphCommand.roomEpoch(),
                graphCommand.processRevision(),
                7,
                "RUNNING",
                null,
                null,
                3,
                graphCommand.deadlineAt(),
                1);
    }

    private Attempt attempt(AgentRunAttemptStatus status) {
        return new Attempt(
                graphCommand.attemptId(),
                graphCommand.logicalRunId(),
                1,
                status,
                false,
                0,
                NOW,
                NOW,
                status == AgentRunAttemptStatus.COMPLETED ? NOW : null,
                1);
    }

    private static AgentRunV2Properties enabled() {
        return properties(true, AgentRunProtocol.V1, SchedulerMode.OFF);
    }

    private static AgentRunV2Properties disabled() {
        return properties(false, AgentRunProtocol.V1, SchedulerMode.EXECUTOR);
    }

    private static AgentRunV2Properties executorMode() {
        return properties(true, AgentRunProtocol.V1, SchedulerMode.EXECUTOR);
    }

    private static AgentRunV2Properties properties(
            boolean enabled, AgentRunProtocol protocol, SchedulerMode mode) {
        return new AgentRunV2Properties(
                enabled,
                protocol,
                mode,
                Duration.ofMinutes(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5));
    }
}
