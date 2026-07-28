package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.temporal.room.hearing.HearingPartyTerminalReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/**
 * CONTROL read boundary for a Java-formal Hearing receipt already committed by the finalization
 * transaction. It may not derive a receipt from a browser command or Graph output.
 */
@ActivityInterface
public interface TargetHearingFormalReceiptRelayActivities {

  @ActivityMethod(name = "RelayTargetHearingFormalReceipt")
  RelayResult relay(RelayRequest request);

  record RelayRequest(
      CaseCommandRef command,
      long roomFencingToken,
      long expectedProcessRevision,
      long expectedRoomRevision,
      HearingWorkflowStage expectedStage) {
    public RelayRequest {
      command = Objects.requireNonNull(command, "command");
      expectedStage = Objects.requireNonNull(expectedStage, "expectedStage");
      if (roomFencingToken < 1 || expectedProcessRevision < 0 || expectedRoomRevision < 0) {
        throw new IllegalArgumentException("target Hearing formal relay coordinates are invalid");
      }
    }
  }

  record RelayResult(HearingStageReceipt stageReceipt, HearingPartyTerminalReceipt partyReceipt) {
    public RelayResult {
      if ((stageReceipt == null) == (partyReceipt == null)) {
        throw new IllegalArgumentException(
            "target Hearing formal relay must contain exactly one formal receipt");
      }
    }
  }
}
