package com.example.dispute.workflow.temporal.room.intake;

public record IntakeCommandDecision(
    String schemaVersion,
    String commandId,
    long sequence,
    String status,
    String reasonCode,
    IntakeRoomPhase roomPhase,
    String requestHash) {

  public IntakeCommandDecision {
    if (!"intake-command-decision.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-command-decision.v1");
    }
  }
}
