package com.example.dispute.workflow.temporal.room.hearing;

import java.util.Objects;

/** Java-committed completion receipt for a non-party stage. */
public record HearingStageReceipt(
    String schemaVersion,
    String receiptId,
    HearingWorkflowStage stage,
    int stageSequence,
    String operationKey,
    String requestHash,
    String resultHash,
    long processRevision,
    long roomRevision,
    long committedEventSequence) {

  public HearingStageReceipt {
    if (!"hearing-stage-receipt.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be hearing-stage-receipt.v1");
    }
    requireText(receiptId, "receiptId");
    Objects.requireNonNull(stage, "stage must not be null");
    if (stageSequence != stage.sequence() || stage.isPartyWait() || stage == HearingWorkflowStage.CLOSED) {
      throw new IllegalArgumentException("receipt stage/sequence is not a completable system stage");
    }
    requireText(operationKey, "operationKey");
    requireHash(requestHash, "requestHash");
    requireHash(resultHash, "resultHash");
    requirePositive(processRevision, "processRevision");
    requirePositive(roomRevision, "roomRevision");
    requirePositive(committedEventSequence, "committedEventSequence");
  }

  static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }

  static void requirePositive(long value, String field) {
    if (value < 1) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }
}
