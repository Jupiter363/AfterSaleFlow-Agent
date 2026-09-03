package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;

/** Atomic Java authority boundary for one typed Intake terminal branch. */
@FunctionalInterface
public interface IntakeFormalBranchCommitPort {

    BranchCommitReceipt commit(BranchCommitRequest request);
}
