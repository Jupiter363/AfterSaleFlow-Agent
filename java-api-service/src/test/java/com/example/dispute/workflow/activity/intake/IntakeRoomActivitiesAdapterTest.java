package com.example.dispute.workflow.activity.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.IntakeFinalizationPersistenceException;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommitPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import io.temporal.failure.ApplicationFailure;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IntakeRoomActivitiesAdapterTest {

    @Test
    void delegatesAllStagesOnlyThroughExplicitPorts() {
        IntakeSnapshotPublicationPort snapshots = mock(IntakeSnapshotPublicationPort.class);
        IntakeGraphExecutionPort graph = mock(IntakeGraphExecutionPort.class);
        IntakeTurnFinalizationPort finalizer = mock(IntakeTurnFinalizationPort.class);
        IntakeFormalBranchCommitPort branches = mock(IntakeFormalBranchCommitPort.class);
        IntakeRoomActivitiesAdapter adapter =
                new IntakeRoomActivitiesAdapter(snapshots, graph, finalizer, branches);

        SnapshotPublicationRequest snapshotRequest = mock(SnapshotPublicationRequest.class);
        SnapshotPublicationReceipt snapshotReceipt = mock(SnapshotPublicationReceipt.class);
        when(snapshots.publish(snapshotRequest)).thenReturn(snapshotReceipt);
        GraphExecutionRequest graphRequest = mock(GraphExecutionRequest.class);
        GraphExecutionReceipt graphReceipt = mock(GraphExecutionReceipt.class);
        when(graph.execute(graphRequest)).thenReturn(graphReceipt);
        TurnFinalizationRequest finalizationRequest = mock(TurnFinalizationRequest.class);
        TurnFinalizationReceipt finalizationReceipt = mock(TurnFinalizationReceipt.class);
        when(finalizer.finalizeTurn(finalizationRequest)).thenReturn(finalizationReceipt);
        BranchCommitRequest branchRequest = mock(BranchCommitRequest.class);
        BranchCommitReceipt branchReceipt = mock(BranchCommitReceipt.class);
        when(branchRequest.operation()).thenReturn(BranchOperation.INITIATOR_ACCEPT);
        when(branches.commit(branchRequest)).thenReturn(branchReceipt);

        assertThat(adapter.publishSnapshot(snapshotRequest)).isSameAs(snapshotReceipt);
        assertThat(adapter.executeGraph(graphRequest)).isSameAs(graphReceipt);
        assertThat(adapter.finalizeTurn(finalizationRequest)).isSameAs(finalizationReceipt);
        assertThat(adapter.acceptInitiator(branchRequest)).isSameAs(branchReceipt);

        verify(finalizationReceipt).requireMatches(finalizationRequest);
        verify(branchReceipt).requireMatches(branchRequest);
    }

    @Test
    void mapsFormalRejectionsToStableNonRetryableTemporalFailures() {
        IntakeFormalBranchCommitPort branches = request -> {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_BRANCH_STALE_FENCE", "stale fence");
        };
        IntakeRoomActivitiesAdapter adapter = new IntakeRoomActivitiesAdapter(
                request -> mock(SnapshotPublicationReceipt.class),
                request -> mock(GraphExecutionReceipt.class),
                request -> mock(TurnFinalizationReceipt.class),
                branches);
        BranchCommitRequest request = mock(BranchCommitRequest.class);
        when(request.operation()).thenReturn(BranchOperation.CANCEL);

        assertThatThrownBy(() -> adapter.cancelIntake(request))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> {
                    ApplicationFailure applicationFailure = (ApplicationFailure) failure;
                    assertThat(applicationFailure.getType()).isEqualTo("INTAKE_BRANCH_STALE_FENCE");
                    assertThat(applicationFailure.isNonRetryable()).isTrue();
                });
    }

    @Test
    void reconciliationAllowsOnlyAnAbsentBranchReceiptToReturnNull() {
        IntakeFormalBranchCommitPort branches = mock(IntakeFormalBranchCommitPort.class);
        IntakeRoomActivitiesAdapter adapter = new IntakeRoomActivitiesAdapter(
                request -> mock(SnapshotPublicationReceipt.class),
                request -> mock(GraphExecutionReceipt.class),
                request -> mock(TurnFinalizationReceipt.class),
                branches);
        BranchCommitRequest reconciliation = mock(BranchCommitRequest.class);
        ActivityEnvelope envelope = mock(ActivityEnvelope.class);
        ActivityInvocation invocation = mock(ActivityInvocation.class);
        when(reconciliation.operation()).thenReturn(BranchOperation.INITIATOR_ACCEPT);
        when(reconciliation.envelope()).thenReturn(envelope);
        when(envelope.invocation()).thenReturn(invocation);
        when(invocation.mode()).thenReturn(ActivityInvocationMode.RECONCILE_ONLY);

        assertThat(adapter.acceptInitiator(reconciliation)).isNull();
        verify(branches).commit(reconciliation);

        BranchCommitRequest execution = mock(BranchCommitRequest.class);
        ActivityEnvelope executionEnvelope = mock(ActivityEnvelope.class);
        ActivityInvocation executionInvocation = mock(ActivityInvocation.class);
        when(execution.operation()).thenReturn(BranchOperation.INITIATOR_ACCEPT);
        when(execution.envelope()).thenReturn(executionEnvelope);
        when(executionEnvelope.invocation()).thenReturn(executionInvocation);
        when(executionInvocation.mode()).thenReturn(ActivityInvocationMode.FIRST_EXECUTION);

        assertThatThrownBy(() -> adapter.acceptInitiator(execution))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> {
                    ApplicationFailure applicationFailure = (ApplicationFailure) failure;
                    assertThat(applicationFailure.getType())
                            .isEqualTo(
                                    IntakeActivityFailureMapper.UNCLASSIFIED_FINALIZATION_FAILURE);
                    assertThat(applicationFailure.isNonRetryable()).isTrue();
                });
    }

    @Test
    void mapsUnresolvedBranchPersistenceToARetryableTemporalFailure() {
        IntakeFormalBranchCommitPort branches = request -> {
            throw new IntakeFinalizationPersistenceException(
                    "branch receipt is not yet resolved", new IllegalStateException("STARTED"));
        };
        IntakeRoomActivitiesAdapter adapter = new IntakeRoomActivitiesAdapter(
                request -> mock(SnapshotPublicationReceipt.class),
                request -> mock(GraphExecutionReceipt.class),
                request -> mock(TurnFinalizationReceipt.class),
                branches);
        BranchCommitRequest request = mock(BranchCommitRequest.class);
        when(request.operation()).thenReturn(BranchOperation.CANCEL);

        assertThatThrownBy(() -> adapter.cancelIntake(request))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(failure -> {
                    ApplicationFailure applicationFailure = (ApplicationFailure) failure;
                    assertThat(applicationFailure.getType())
                            .isEqualTo(
                                    IntakeActivityFailureMapper.RETRYABLE_FINALIZATION_PERSISTENCE);
                    assertThat(applicationFailure.isNonRetryable()).isFalse();
                });
    }

    @Test
    void hasNoFrameworkDiscoveryAnnotation() {
        assertThat(Arrays.stream(IntakeRoomActivitiesAdapter.class.getAnnotations())
                        .map(annotation -> annotation.annotationType().getPackageName()))
                .noneMatch(name -> name.startsWith("org.springframework"));
    }
}
