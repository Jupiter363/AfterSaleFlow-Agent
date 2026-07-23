package com.example.dispute.workflow.temporal.room.hearing;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import java.util.Objects;

/** Java-committed receipt for a non-party Hearing operation. */
public record HearingStageReceipt(
    String schemaVersion,
    HearingCommittedReceipt committed) {

  public static final String SCHEMA_VERSION = "hearing-stage-receipt.v1";

  public HearingStageReceipt {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
    }
    Objects.requireNonNull(committed, "committed must not be null");
    if (committed.operationType() == HearingAuthorityCommit.OperationType.PARTY_TERMINAL) {
      throw new IllegalArgumentException("party receipt cannot use the stage Signal");
    }
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
