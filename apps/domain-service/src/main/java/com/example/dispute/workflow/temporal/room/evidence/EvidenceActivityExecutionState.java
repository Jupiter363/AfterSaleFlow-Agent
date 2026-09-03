package com.example.dispute.workflow.temporal.room.evidence;

import java.util.Objects;

/** Durable Workflow-side state for one receipt-bound Activity operation. */
public record EvidenceActivityExecutionState(
    String schemaVersion,
    EvidenceActivityProtocol.ActivityRequest request,
    Status status,
    int receiptLookupAttempts,
    EvidenceFinalizationReceiptRef committedReceipt,
    RecoveryReason recoveryReason) {

  public static final int MAX_RECEIPT_LOOKUPS = 3;

  public EvidenceActivityExecutionState {
    if (!"evidence-activity-execution-state.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be evidence-activity-execution-state.v1");
    }
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(recoveryReason, "recoveryReason must not be null");
    if (receiptLookupAttempts < 0 || receiptLookupAttempts > MAX_RECEIPT_LOOKUPS) {
      throw new IllegalArgumentException("receiptLookupAttempts is outside its bounded retry budget");
    }
    if (status == Status.COMMITTED_RECEIPT_CONFIRMED) {
      if (committedReceipt == null || !committedReceipt.matches(request)) {
        throw new IllegalArgumentException("confirmed state requires a matching committed receipt");
      }
      if (recoveryReason != RecoveryReason.NONE) {
        throw new IllegalArgumentException("confirmed state cannot retain a recovery reason");
      }
    } else if (committedReceipt != null) {
      throw new IllegalArgumentException("unconfirmed state cannot carry a committed receipt");
    }
  }

  public static EvidenceActivityExecutionState pending(
      EvidenceActivityProtocol.ActivityRequest request) {
    return new EvidenceActivityExecutionState(
        "evidence-activity-execution-state.v1",
        request,
        Status.PENDING_RECEIPT_LOOKUP,
        0,
        null,
        RecoveryReason.NONE);
  }

  EvidenceActivityExecutionState awaitingReceipt(RecoveryReason reason) {
    if (status == Status.COMMITTED_RECEIPT_CONFIRMED) {
      return this;
    }
    return new EvidenceActivityExecutionState(
        schemaVersion,
        request,
        reason == RecoveryReason.CANCELLATION
            ? Status.CANCELLATION_RECONCILING
            : Status.RECONCILING_RECEIPT,
        receiptLookupAttempts,
        null,
        reason);
  }

  EvidenceActivityExecutionState recordLookupMiss() {
    if (status == Status.COMMITTED_RECEIPT_CONFIRMED) {
      return this;
    }
    int nextAttempts = receiptLookupAttempts + 1;
    if (nextAttempts >= MAX_RECEIPT_LOOKUPS) {
      return new EvidenceActivityExecutionState(
          schemaVersion,
          request,
          Status.RECEIPT_UNCONFIRMED,
          nextAttempts,
          null,
          RecoveryReason.LOOKUP_MISS);
    }
    return new EvidenceActivityExecutionState(
        schemaVersion,
        request,
        Status.RECONCILING_RECEIPT,
        nextAttempts,
        null,
        RecoveryReason.LOOKUP_MISS);
  }

  EvidenceActivityExecutionState confirm(EvidenceFinalizationReceiptRef receipt) {
    if (receiptLookupAttempts >= MAX_RECEIPT_LOOKUPS) {
      throw new IllegalStateException("receipt lookup retry budget is exhausted");
    }
    return new EvidenceActivityExecutionState(
        schemaVersion,
        request,
        Status.COMMITTED_RECEIPT_CONFIRMED,
        receiptLookupAttempts + 1,
        receipt,
        RecoveryReason.NONE);
  }

  public boolean permitsProgress() {
    return status == Status.COMMITTED_RECEIPT_CONFIRMED;
  }

  public enum Status {
    PENDING_RECEIPT_LOOKUP,
    RECONCILING_RECEIPT,
    CANCELLATION_RECONCILING,
    RECEIPT_UNCONFIRMED,
    COMMITTED_RECEIPT_CONFIRMED
  }

  public enum RecoveryReason {
    NONE,
    ACTIVITY_RESPONSE_LOST,
    CANCELLATION,
    LOOKUP_MISS
  }
}
