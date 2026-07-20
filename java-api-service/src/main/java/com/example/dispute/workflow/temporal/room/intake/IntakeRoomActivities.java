package com.example.dispute.workflow.temporal.room.intake;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface IntakeRoomActivities {

  @ActivityMethod(name = "PublishIntakeSnapshot")
  SnapshotPublicationReceipt publishSnapshot(SnapshotPublicationRequest request);

  @ActivityMethod(name = "ExecuteIntakeGraph")
  GraphExecutionReceipt executeGraph(GraphExecutionRequest request);

  @ActivityMethod(name = "FinalizeIntakeTurn")
  TurnFinalizationReceipt finalizeTurn(TurnFinalizationRequest request);

  @ActivityMethod(name = "CommitIntakeInitiatorAcceptance")
  BranchCommitReceipt acceptInitiator(BranchCommitRequest request);

  @ActivityMethod(name = "CommitIntakeInitiatorRejection")
  BranchCommitReceipt rejectInitiator(BranchCommitRequest request);

  @ActivityMethod(name = "CommitIntakeCancellation")
  BranchCommitReceipt cancelIntake(BranchCommitRequest request);

  @ActivityMethod(name = "CommitIntakeRespondentConfirmation")
  BranchCommitReceipt confirmRespondent(BranchCommitRequest request);
}
