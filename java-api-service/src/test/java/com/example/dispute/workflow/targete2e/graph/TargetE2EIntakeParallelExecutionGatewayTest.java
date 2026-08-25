package com.example.dispute.workflow.targete2e.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ProgressListener;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient.FrameExecutionReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.TerminalReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.targete2e.graph.TargetE2EIntakeParallelAssemblyCoordinator.AssemblyResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetE2EIntakeParallelExecutionGatewayTest {

    @Test
    void resumesOnlyMissingFramesThenPublishesOneAtomicDurableCompletion() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        GraphReconcileResponse response = response(request);
        var durable = AgentRunPersistenceFixtures.parallelIntakeResult(5L);
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        TargetE2EIntakeParallelAssemblyCoordinator coordinator =
                mock(TargetE2EIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        AssemblyResult assembly = mock(AssemblyResult.class);
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"))
                .thenReturn(response);
        when(frames.executeOrResume(eq(request), any(), any()))
                .thenReturn(new FrameExecutionReceipt("IPFS_TEST", 4L, true));
        when(coordinator.assembleReady(eq(request), eq("IPFS_TEST"), any()))
                .thenReturn(assembly);
        when(terminal.appendOrLoad(any())).thenReturn(terminal(durable, response));
        List<AgentRunProgress> progress = new ArrayList<>();

        var completion = new TargetE2EIntakeParallelExecutionGateway(
                        frames, coordinator, reconciliation, terminal)
                .execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        progress::add,
                        new AgentRunCancellationToken());

        assertThat(completion.durableResult()).isSameAs(durable);
        assertThat(progress)
                .extracting(AgentRunProgress::lastSequenceNo)
                .containsExactly(4L, 5L);
        assertThat(progress.getLast().finalFrameObserved()).isTrue();
        verify(frames).executeOrResume(eq(request), any(), any());
        verify(coordinator).assembleReady(eq(request), eq("IPFS_TEST"), any());
        verify(reconciliation, times(2)).reconcile(eq(request), any());
        verify(terminal).appendOrLoad(any());
    }

    @Test
    void reconcileOnlyUsesImmutableReadyAndNeverStartsAProviderLane() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        GraphReconcileResponse response = response(request);
        var durable = AgentRunPersistenceFixtures.parallelIntakeResult(5L);
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        TargetE2EIntakeParallelAssemblyCoordinator coordinator =
                mock(TargetE2EIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(reconciliation.reconcile(eq(request), any())).thenReturn(response);
        when(terminal.appendOrLoad(any())).thenReturn(terminal(durable, response));

        var completion = new TargetE2EIntakeParallelExecutionGateway(
                        frames, coordinator, reconciliation, terminal)
                .execute(
                        request,
                        ExecutionMode.RECONCILE_ONLY,
                        ignored -> {},
                        new AgentRunCancellationToken());

        assertThat(completion.durableResult()).isSameAs(durable);
        verify(frames, never()).executeOrResume(any(), any(), any());
        verify(coordinator, never()).assembleReady(any(), any(), any());
        verify(reconciliation).reconcile(eq(request), any());
    }

    @Test
    void preservesRemoteFailureAuthorityAndDurableParallelProgress() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        TargetE2EIntakeParallelAssemblyCoordinator coordinator =
                mock(TargetE2EIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any())).thenAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onProgress(new AgentRunProgress(2L, true, false));
            throw TargetE2EGraphClientException.remote(
                    "GRAPH_BULKHEAD_SCOPE_INVALID", false, "rejected");
        });
        List<AgentRunProgress> progress = new ArrayList<>();

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new TargetE2EIntakeParallelExecutionGateway(
                                frames, coordinator, reconciliation, terminal)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                progress::add,
                                new AgentRunCancellationToken()),
                AgentRunExecutionException.class);

        assertThat(failure.errorCode()).isEqualTo("GRAPH_BULKHEAD_SCOPE_INVALID");
        assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(failure.lastSequenceNo()).isEqualTo(2L);
        assertThat(failure.publicOutputEmitted()).isTrue();
        assertThat(progress).hasSize(1);
        verify(coordinator, never()).assembleReady(any(), any(), any());
        verify(terminal, never()).appendOrLoad(any());
    }

    @Test
    void unclassifiedFailureAfterPublicOutputRequiresReconciliationInsteadOfProviderReplay() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        TargetE2EIntakeParallelAssemblyCoordinator coordinator =
                mock(TargetE2EIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any())).thenAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onProgress(new AgentRunProgress(3L, true, false));
            throw new IllegalStateException("unclassified V4 boundary failure");
        });

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new TargetE2EIntakeParallelExecutionGateway(
                                frames, coordinator, reconciliation, terminal)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()),
                AgentRunExecutionException.class);

        assertThat(failure.errorCode()).isEqualTo("TARGET_E2E_GRAPH_TRANSPORT_FAILED");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL);
        assertThat(failure.lastSequenceNo()).isEqualTo(3L);
        assertThat(failure.publicOutputEmitted()).isTrue();
        verify(coordinator, never()).assembleReady(any(), any(), any());
        verify(terminal, never()).appendOrLoad(any());
    }

    @Test
    void unclassifiedFailureBeforePublicOutputRetainsAV4RetryCode() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        TargetE2EIntakeParallelAssemblyCoordinator coordinator =
                mock(TargetE2EIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any()))
                .thenThrow(new IllegalStateException("unclassified V4 boundary failure"));

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new TargetE2EIntakeParallelExecutionGateway(
                                frames, coordinator, reconciliation, terminal)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()),
                AgentRunExecutionException.class);

        assertThat(failure.errorCode()).isEqualTo("INTAKE_PARALLEL_EXECUTION_UNCLASSIFIED");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RETRY_SAME_COMMAND);
        assertThat(failure.lastSequenceNo()).isEqualTo(-1L);
        assertThat(failure.publicOutputEmitted()).isFalse();
        verify(coordinator, never()).assembleReady(any(), any(), any());
        verify(terminal, never()).appendOrLoad(any());
    }

    private static GraphReconcileResponse response(ExecuteAgentRunRequest request) {
        RoomGraphCommand command = request.command();
        RoomGraphResult result = AgentRunPersistenceFixtures.parallelIntakeGraphResult();
        return new GraphReconcileResponse(
                "graph-reconcile-response.v1",
                GraphReconcileResponse.Disposition.RETURN_CACHED,
                command.threadId(),
                command.commandId(),
                command.requestHash(),
                request.logicalRunId(),
                request.attemptId(),
                command.graphKey(),
                command.graphVersion(),
                command.checkpointSchemaVersion(),
                "intake-parallel-ready",
                result.checkpointId(),
                "urn:target-e2e:result:intake:" + result.outputHash(),
                result.outputHash(),
                "9".repeat(64),
                "tools.none.v1",
                result);
    }

    private static TerminalReceipt terminal(
            com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult result,
            GraphReconcileResponse response) {
        return new TerminalReceipt(
                result,
                response.resultRef(),
                "IPFTR_TEST",
                "e".repeat(64),
                "f".repeat(64),
                true,
                result.lastSequenceNo());
    }
}
