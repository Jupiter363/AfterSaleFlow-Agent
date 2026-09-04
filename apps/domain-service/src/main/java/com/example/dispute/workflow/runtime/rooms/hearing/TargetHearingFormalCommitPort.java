package com.example.dispute.workflow.runtime.rooms.hearing;

import java.sql.Connection;

/** Caller-transaction formal writer; target receipt and command completion are intentionally absent. */
@FunctionalInterface
public interface TargetHearingFormalCommitPort {
  CommitResult commit(Connection transaction, TargetHearingFinalizationRequest request);

  record CommitResult(String formalObjectId, String formalReceiptHash) {
    public CommitResult {
      if (formalObjectId == null || formalObjectId.isBlank()
          || formalReceiptHash == null || !formalReceiptHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target Hearing formal commit result is invalid");
      }
    }
  }
}
