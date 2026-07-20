package com.example.dispute.workflow.temporal.room.intake;

public enum IntakeActivityStage {
  SNAPSHOT_PUBLICATION,
  GRAPH_EXECUTION,
  TURN_FINALIZATION,
  INITIATOR_ACCEPTANCE,
  INITIATOR_REJECTION,
  CANCELLATION,
  RESPONDENT_CONFIRMATION
}
