package com.example.dispute.workflow.temporal.room.outcome;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeProjection;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import java.util.Objects;

/** Terminal, Java-durable result of the post-routing target Outcome completion Update. */
public record OutcomeCompletionResult(
    OutcomeProjection terminalProjection, TargetRoomProgressReceipt terminalProgressReceipt) {

  public OutcomeCompletionResult {
    terminalProjection = Objects.requireNonNull(terminalProjection, "terminalProjection");
    terminalProgressReceipt = Objects.requireNonNull(terminalProgressReceipt, "terminalProgressReceipt");
    if (terminalProjection.phase() != OutcomeWireTypes.ProjectionPhase.EVALUATED
        || terminalProgressReceipt.roomType()
            != com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.REVIEW
        || terminalProjection.epoch() != terminalProgressReceipt.roomEpoch()
        || terminalProjection.fence() != terminalProgressReceipt.fencingToken()) {
      throw new IllegalArgumentException("target Outcome completion result is not terminal and exact");
    }
  }
}
