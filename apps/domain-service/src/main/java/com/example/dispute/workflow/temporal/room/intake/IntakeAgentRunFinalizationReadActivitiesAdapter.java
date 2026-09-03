package com.example.dispute.workflow.temporal.room.intake;

import java.util.Objects;

/** Non-discoverable Activity adapter; target worker assembly must register it explicitly. */
public final class IntakeAgentRunFinalizationReadActivitiesAdapter
    implements IntakeAgentRunFinalizationReadActivities {

  private final IntakeAgentRunFinalizationReceiptReadPort readPort;

  public IntakeAgentRunFinalizationReadActivitiesAdapter(
      IntakeAgentRunFinalizationReceiptReadPort readPort) {
    this.readPort = Objects.requireNonNull(readPort, "readPort");
  }

  @Override
  public IntakeAgentRunFinalizationReadResult readFinalization(
      IntakeAgentRunFinalizationReadRequest request) {
    IntakeAgentRunFinalizationReadResult result = readPort.read(request);
    if (result == null) {
      throw new IllegalStateException("finalization read port returned no resolution");
    }
    result.requireMatches(request, request.allowsWinningAttempt());
    return result;
  }
}
