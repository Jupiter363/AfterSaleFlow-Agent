package com.example.dispute.workflow.temporal.room.hearing;

import java.util.Objects;

/** Stable operation keys shared by Workflow signals and Java receipts. */
public final class HearingOperationKeys {

  private HearingOperationKeys() {}

  public static String stageCompletion(
      String caseId, long roomEpoch, HearingWorkflowStage stage, int sequence) {
    return "hearing.stage.complete:"
        + required(caseId, "caseId")
        + ":"
        + positive(roomEpoch, "roomEpoch")
        + ":"
        + Objects.requireNonNull(stage, "stage must not be null").name()
        + ":"
        + positive(sequence, "sequence");
  }

  public static String partyTerminal(
      String caseId,
      long roomEpoch,
      HearingWorkflowStage stage,
      int sequence,
      String participantId,
      String requestId) {
    return "hearing.party.terminal:"
        + required(caseId, "caseId")
        + ":"
        + positive(roomEpoch, "roomEpoch")
        + ":"
        + Objects.requireNonNull(stage, "stage must not be null").name()
        + ":"
        + positive(sequence, "sequence")
        + ":"
        + required(participantId, "participantId")
        + ":"
        + required(requestId, "requestId");
  }

  public static String partyDeadline(
      String caseId, long roomEpoch, HearingWorkflowStage stage, int sequence) {
    return "hearing.party.deadline:"
        + required(caseId, "caseId")
        + ":"
        + positive(roomEpoch, "roomEpoch")
        + ":"
        + Objects.requireNonNull(stage, "stage must not be null").name()
        + ":"
        + positive(sequence, "sequence");
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank() || value.indexOf(':') >= 0) {
      throw new IllegalArgumentException(field + " must be non-blank and contain no colon");
    }
    return value;
  }

  private static long positive(long value, String field) {
    if (value < 1) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }
}
