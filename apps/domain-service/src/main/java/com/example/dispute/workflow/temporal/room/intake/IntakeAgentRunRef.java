package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;

public record IntakeAgentRunRef(
    String schemaVersion, String logicalRunId, String attemptId, String finalResultHash) {

  public IntakeAgentRunRef {
    if (!"intake-agent-run-ref.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-agent-run-ref.v1");
    }
    requireIdentifier(logicalRunId, "logicalRunId");
    requireIdentifier(attemptId, "attemptId");
    requireHash(finalResultHash, "finalResultHash");
  }
}
