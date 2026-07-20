package com.example.dispute.workflow.temporal.room.intake;

public enum IntakeReceiptType {
  TURN_NEEDS_INPUT,
  TURN_READY_TO_CONFIRM,
  INITIATOR_ACCEPTED,
  NOT_ADMISSIBLE,
  CANCELLED,
  RESPONDENT_CONFIRMED
}
