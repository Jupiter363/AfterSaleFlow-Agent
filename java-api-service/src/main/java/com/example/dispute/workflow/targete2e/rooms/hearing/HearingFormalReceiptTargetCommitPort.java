package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import java.sql.Connection;
import java.util.Objects;

/** Bridges the existing Hearing formal receipt service into the shared target finalization tx. */
public final class HearingFormalReceiptTargetCommitPort implements TargetHearingFormalCommitPort {
  private final TargetHearingFormalCompletion completion;

  public HearingFormalReceiptTargetCommitPort(TargetHearingFormalCompletion completion) {
    this.completion = Objects.requireNonNull(completion, "completion");
  }

  @Override
  public CommitResult commit(Connection transaction, TargetHearingFinalizationRequest request) {
    if (transaction == null) {
      throw new IllegalArgumentException("target Hearing formal commit requires the outer transaction");
    }
    request = Objects.requireNonNull(request, "request");
    HearingStageReceipt receipt = completion.commit(request.formalCommand());
    return new CommitResult(request.formalObjectId(), receipt.committed().receiptHash());
  }
}
