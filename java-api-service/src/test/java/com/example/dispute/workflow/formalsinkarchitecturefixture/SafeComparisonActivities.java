package com.example.dispute.workflow.formalsinkarchitecturefixture;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;

final class SafeComparisonActivities implements IntakeRoomActivities {

    private final ComparisonSink comparisons;

    SafeComparisonActivities(ComparisonSink comparisons) {
        this.comparisons = comparisons;
    }

    @Override
    public SnapshotPublicationReceipt publishSnapshot(SnapshotPublicationRequest request) {
        comparisons.append(request);
        return null;
    }

    @Override
    public GraphExecutionReceipt executeGraph(GraphExecutionRequest request) {
        comparisons.append(request);
        return null;
    }

    @Override
    public TurnFinalizationReceipt finalizeTurn(TurnFinalizationRequest request) {
        comparisons.append(request);
        return null;
    }

    @Override
    public BranchCommitReceipt acceptInitiator(BranchCommitRequest request) {
        comparisons.append(request);
        return null;
    }

    @Override
    public BranchCommitReceipt rejectInitiator(BranchCommitRequest request) {
        comparisons.append(request);
        return null;
    }

    @Override
    public BranchCommitReceipt cancelIntake(BranchCommitRequest request) {
        comparisons.append(request);
        return null;
    }

    @Override
    public BranchCommitReceipt confirmRespondent(BranchCommitRequest request) {
        comparisons.append(request);
        return null;
    }

    interface ComparisonSink {
        void append(Object comparison);
    }
}
