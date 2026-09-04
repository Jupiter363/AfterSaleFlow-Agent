package com.example.dispute.workflow.runtime.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException.FailureAuthority;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ProgressListener;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.FailureTerminationReceipt;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient.FrameExecutionReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient.LocalReconciliationException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.DurableProgress;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.TerminalReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.runtime.graph.ProductionIntakeParallelAssemblyCoordinator.AssemblyResult;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProductionIntakeParallelExecutionGatewayTest {

    @Test
    void localStagingConflictRetainsLocalAuthorityWithoutInventingACompletion() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(terminal.loadProgress(request)).thenReturn(emptyProgress());
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any()))
                .thenThrow(new LocalReconciliationException(
                        "INTAKE_PARALLEL_RETRY_AUTHORITY_INVALID",
                        "technical staging requires local reconciliation",
                        null));

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new ProductionIntakeParallelExecutionGateway(
                                frames, coordinator, reconciliation, terminal)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()),
                AgentRunExecutionException.class);

        assertThat(failure.failureAuthority()).isEqualTo(FailureAuthority.LOCAL_RECONCILIATION);
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RETRY_SAME_COMMAND);
        assertThat(failure.terminalAuthorityObserved()).isFalse();
        assertThat(failure.lastSequenceNo()).isEqualTo(-1L);
        verifyNoInteractions(coordinator);
    }

    @Test
    void resumesOnlyMissingFramesThenPublishesOneAtomicDurableCompletion() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        GraphReconcileResponse response = response(request);
        var durable = AgentRunPersistenceFixtures.parallelIntakeResult(5L);
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(terminal.loadProgress(request)).thenReturn(emptyProgress());
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

        var completion = new ProductionIntakeParallelExecutionGateway(
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
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(terminal.loadProgress(request)).thenReturn(emptyProgress());
        when(reconciliation.reconcile(eq(request), any())).thenReturn(response);
        when(terminal.appendOrLoad(any())).thenReturn(terminal(durable, response));

        var completion = new ProductionIntakeParallelExecutionGateway(
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
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(terminal.loadProgress(request)).thenReturn(emptyProgress());
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any())).thenAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onProgress(new AgentRunProgress(2L, true, false));
            throw ProductionGraphClientException.remote(
                    "GRAPH_BULKHEAD_SCOPE_INVALID", false, "rejected");
        });
        List<AgentRunProgress> progress = new ArrayList<>();

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new ProductionIntakeParallelExecutionGateway(
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
    void normalizesRemoteCreateNextAttemptToSameAttemptLaneRecovery() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(terminal.loadProgress(request)).thenReturn(emptyProgress());
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any()))
                .thenThrow(ProductionGraphClientException.attemptAborted("GRAPH_LEASE_LOST"));

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new ProductionIntakeParallelExecutionGateway(
                                frames, coordinator, reconciliation, terminal)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()),
                AgentRunExecutionException.class);

        assertThat(failure.errorCode()).isEqualTo("GRAPH_LEASE_LOST");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RETRY_SAME_COMMAND);
        assertThat(failure.failureAuthority()).isEqualTo(FailureAuthority.EXECUTION);
        verify(coordinator, never()).assembleReady(any(), any(), any());
    }

    @Test
    void keepsPostSealAssemblyConflictAsJavaLocalReconciliationAuthority() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(terminal.loadProgress(request)).thenReturn(emptyProgress());
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any()))
                .thenReturn(new FrameExecutionReceipt("IPFS_TEST", 4L, true));
        when(coordinator.assembleReady(eq(request), eq("IPFS_TEST"), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_ASSEMBLY_HASH_DRIFT", "hash drift"));

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new ProductionIntakeParallelExecutionGateway(
                                frames, coordinator, reconciliation, terminal)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()),
                AgentRunExecutionException.class);

        assertThat(failure.errorCode()).isEqualTo("INTAKE_PARALLEL_ASSEMBLY_HASH_DRIFT");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL);
        assertThat(failure.failureAuthority()).isEqualTo(FailureAuthority.LOCAL_RECONCILIATION);
        verify(terminal, never()).appendOrLoad(any());
    }

    @Test
    void delegatesFailureTerminationWithoutTouchingAssemblyOrTerminalState() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        FailureTerminationReceipt receipt = new FailureTerminationReceipt(
                "intake.parallel-failure-termination.v1",
                "parallel-failure-terminal.test",
                "e".repeat(64),
                "{}".getBytes(StandardCharsets.UTF_8));
        when(frames.terminateUncommittedFailure(eq(request), any(), any()))
                .thenReturn(receipt);

        var actual = new ProductionIntakeParallelExecutionGateway(
                        frames, coordinator, reconciliation, terminal)
                .terminateUncommittedFailure(
                        request,
                        "INTAKE_PARALLEL_FRAME_BATCH_FAILED",
                        new AgentRunCancellationToken());

        assertThat(actual).containsSame(receipt);
        verify(frames).terminateUncommittedFailure(eq(request), any(), any());
        verifyNoInteractions(coordinator, reconciliation, terminal);
    }

    @Test
    void unclassifiedFailureAfterPublicOutputRequiresReconciliationInsteadOfProviderReplay() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(terminal.loadProgress(request)).thenReturn(emptyProgress());
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any())).thenAnswer(invocation -> {
            ProgressListener listener = invocation.getArgument(1);
            listener.onProgress(new AgentRunProgress(3L, true, false));
            throw new IllegalStateException("unclassified V4 boundary failure");
        });

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new ProductionIntakeParallelExecutionGateway(
                                frames, coordinator, reconciliation, terminal)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()),
                AgentRunExecutionException.class);

        assertThat(failure.errorCode()).isEqualTo("PRODUCTION_RUNTIME_GRAPH_TRANSPORT_FAILED");
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
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(terminal.loadProgress(request)).thenReturn(emptyProgress());
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any()))
                .thenThrow(new IllegalStateException("unclassified V4 boundary failure"));

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new ProductionIntakeParallelExecutionGateway(
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

    @Test
    void reloadsDurableProgressWhenIngressCommittedBeforeItsCallbackFailed() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        IntakeParallelFrameExecutionClient frames = mock(IntakeParallelFrameExecutionClient.class);
        ProductionIntakeParallelAssemblyCoordinator coordinator =
                mock(ProductionIntakeParallelAssemblyCoordinator.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeParallelRunTerminalStore terminal = mock(IntakeParallelRunTerminalStore.class);
        when(terminal.loadProgress(request))
                .thenReturn(emptyProgress())
                .thenReturn(new DurableProgress(7L, true, false));
        when(reconciliation.reconcile(eq(request), any()))
                .thenThrow(new AssemblyConflictException(
                        "INTAKE_PARALLEL_READY_MISSING", "not ready"));
        when(frames.executeOrResume(eq(request), any(), any()))
                .thenThrow(new IllegalStateException("callback failed after durable ingress"));

        AgentRunExecutionException failure = catchThrowableOfType(
                () -> new ProductionIntakeParallelExecutionGateway(
                                frames, coordinator, reconciliation, terminal)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()),
                AgentRunExecutionException.class);

        assertThat(failure.errorCode()).isEqualTo("PRODUCTION_RUNTIME_GRAPH_TRANSPORT_FAILED");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL);
        assertThat(failure.lastSequenceNo()).isEqualTo(7L);
        assertThat(failure.publicOutputEmitted()).isTrue();
        verify(terminal, times(2)).loadProgress(request);
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
                "urn:production-runtime:result:intake:" + result.outputHash(),
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

    private static DurableProgress emptyProgress() {
        return new DurableProgress(-1L, false, false);
    }
}
