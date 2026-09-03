package com.example.dispute.workflow.temporal.room.hearing;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import java.util.Objects;

/** Browser-originated party input awaiting Java formalization at a party-wait Hearing stage. */
public record HearingPartyCommand(
    CaseCommandRef command, long fencingToken, long expectedProcessRevision, long expectedRoomRevision) {
  public HearingPartyCommand {
    command = Objects.requireNonNull(command, "command");
    if (fencingToken < 1 || expectedProcessRevision < 0 || expectedRoomRevision < 0) {
      throw new IllegalArgumentException("Hearing party command coordinates are invalid");
    }
  }
}
