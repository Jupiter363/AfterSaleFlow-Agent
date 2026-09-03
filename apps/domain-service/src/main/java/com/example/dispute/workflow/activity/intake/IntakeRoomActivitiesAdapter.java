package com.example.dispute.workflow.activity.intake;

import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommitPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Production-shaped Intake Activity implementation with explicit, non-discoverable ports.
 *
 * <p>The class deliberately has no framework stereotype. Phase 4 tests may assemble it directly;
 * the current worker must not register an instance or make the formal ports reachable from SHADOW.
 */
public final class IntakeRoomActivitiesAdapter implements IntakeRoomActivities {

    private final IntakeSnapshotPublicationPort snapshots;
    private final IntakeGraphExecutionPort graph;
    private final IntakeTurnFinalizationPort finalizer;
    private final IntakeFormalBranchCommitPort branches;

    public IntakeRoomActivitiesAdapter(
            IntakeSnapshotPublicationPort snapshots,
            IntakeGraphExecutionPort graph,
            IntakeTurnFinalizationPort finalizer,
            IntakeFormalBranchCommitPort branches) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        this.branches = Objects.requireNonNull(branches, "branches");
    }

    @Override
    public SnapshotPublicationReceipt publishSnapshot(SnapshotPublicationRequest request) {
        return invoke(() -> snapshots.publish(Objects.requireNonNull(request, "request")));
    }

    @Override
    public GraphExecutionReceipt executeGraph(GraphExecutionRequest request) {
        return invoke(() -> graph.execute(Objects.requireNonNull(request, "request")));
    }

    @Override
    public TurnFinalizationReceipt finalizeTurn(TurnFinalizationRequest request) {
        return invoke(() -> {
            TurnFinalizationReceipt receipt = finalizer.finalizeTurn(
                    Objects.requireNonNull(request, "request"));
            Objects.requireNonNull(receipt, "finalizer receipt").requireMatches(request);
            return receipt;
        });
    }

    @Override
    public BranchCommitReceipt acceptInitiator(BranchCommitRequest request) {
        return commit(request, BranchOperation.INITIATOR_ACCEPT);
    }

    @Override
    public BranchCommitReceipt rejectInitiator(BranchCommitRequest request) {
        return commit(request, BranchOperation.INITIATOR_REJECT);
    }

    @Override
    public BranchCommitReceipt cancelIntake(BranchCommitRequest request) {
        return commit(request, BranchOperation.CANCEL);
    }

    @Override
    public BranchCommitReceipt confirmRespondent(BranchCommitRequest request) {
        return commit(request, BranchOperation.RESPONDENT_CONFIRM);
    }

    private BranchCommitReceipt commit(
            BranchCommitRequest request, BranchOperation expectedOperation) {
        try {
            Objects.requireNonNull(request, "request");
            if (request.operation() != expectedOperation) {
                throw new IllegalArgumentException(
                        "Activity method does not match the requested Intake branch");
            }
            BranchCommitReceipt receipt = branches.commit(request);
            if (receipt == null) {
                if (request.envelope().invocation().mode()
                        == ActivityInvocationMode.RECONCILE_ONLY) {
                    return null;
                }
                throw new NullPointerException("branch receipt");
            }
            receipt.requireMatches(request);
            return receipt;
        } catch (RuntimeException failure) {
            throw IntakeActivityFailureMapper.toApplicationFailure(failure);
        }
    }

    private static <T> T invoke(Supplier<T> invocation) {
        try {
            return Objects.requireNonNull(invocation.get(), "Activity port returned null");
        } catch (RuntimeException failure) {
            throw IntakeActivityFailureMapper.toApplicationFailure(failure);
        }
    }
}
