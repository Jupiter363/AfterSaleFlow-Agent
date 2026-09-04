package com.example.dispute.workflow.runtime.rooms.intake;

import com.example.dispute.workflow.activity.intake.IntakeActivityFailureMapper;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeFormalBranchCommitPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import java.util.Objects;

/** Target control-lane Activity surface: only formal branch commits are reachable here. */
public final class ProductionIntakeRoomActivities implements IntakeRoomActivities {
  private final IntakeFormalBranchCommitPort branches;

  public ProductionIntakeRoomActivities(IntakeFormalBranchCommitPort branches) {
    this.branches = Objects.requireNonNull(branches, "branches");
  }

  @Override
  public SnapshotPublicationReceipt publishSnapshot(SnapshotPublicationRequest request) {
    throw unexpected("PublishIntakeSnapshot");
  }

  @Override
  public GraphExecutionReceipt executeGraph(GraphExecutionRequest request) {
    throw unexpected("ExecuteIntakeGraph");
  }

  @Override
  public TurnFinalizationReceipt finalizeTurn(TurnFinalizationRequest request) {
    throw unexpected("FinalizeIntakeTurn");
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

  private BranchCommitReceipt commit(BranchCommitRequest request, BranchOperation expectedOperation) {
    try {
      Objects.requireNonNull(request, "request");
      if (request.operation() != expectedOperation) {
        throw new IntakeFinalizationRejectedException(
            "PRODUCTION_RUNTIME_BRANCH_ACTIVITY_OPERATION_INVALID",
            "target Intake branch activity method does not match request operation");
      }
      BranchCommitReceipt receipt = branches.commit(request);
      if (receipt == null) {
        throw new IntakeFinalizationRejectedException(
            "PRODUCTION_RUNTIME_BRANCH_ACTIVITY_RECEIPT_MISSING",
            "target Intake branch activity returned no receipt");
      }
      receipt.requireMatches(request);
      return receipt;
    } catch (RuntimeException failure) {
      throw IntakeActivityFailureMapper.toApplicationFailure(failure);
    }
  }

  private static RuntimeException unexpected(String activity) {
    return IntakeActivityFailureMapper.toApplicationFailure(
        new IntakeFinalizationRejectedException(
            "PRODUCTION_RUNTIME_INTAKE_ACTIVITY_UNEXPECTED",
            activity + " is not permitted in the target control lane"));
  }
}
