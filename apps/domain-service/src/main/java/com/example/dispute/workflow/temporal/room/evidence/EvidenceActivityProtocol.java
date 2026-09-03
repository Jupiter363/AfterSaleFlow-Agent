package com.example.dispute.workflow.temporal.room.evidence;

import java.util.Objects;

/**
 * Deterministic receipt-reconciliation protocol. Activities can report a Java-ledger receipt,
 * but Workflow history alone can never turn an operation into a formal or synthetic commit.
 */
public final class EvidenceActivityProtocol {

  private EvidenceActivityProtocol() {}

  public static EvidenceActivityExecutionState begin(ActivityRequest request) {
    return EvidenceActivityExecutionState.pending(request);
  }

  public static EvidenceActivityExecutionState activityResponseLost(
      EvidenceActivityExecutionState state) {
    return requireState(state)
        .awaitingReceipt(EvidenceActivityExecutionState.RecoveryReason.ACTIVITY_RESPONSE_LOST);
  }

  public static EvidenceActivityExecutionState cancellationRequested(
      EvidenceActivityExecutionState state) {
    return requireState(state)
        .awaitingReceipt(EvidenceActivityExecutionState.RecoveryReason.CANCELLATION);
  }

  public static EvidenceActivityExecutionState reconcile(
      EvidenceActivityExecutionState state,
      ActivityRequest request,
      ReceiptLookupResult lookupResult) {
    EvidenceActivityExecutionState current = requireState(state);
    requireSameRequest(current.request(), request);
    Objects.requireNonNull(lookupResult, "lookupResult must not be null");

    if (current.permitsProgress()) {
      if (lookupResult.status() == ReceiptLookupStatus.COMMITTED
          && !current.committedReceipt().equals(lookupResult.receipt())) {
        throw new ProtocolRejectedException(Violation.COMMITTED_RECEIPT_CONFLICT);
      }
      return current;
    }
    if (lookupResult.status() == ReceiptLookupStatus.NOT_COMMITTED) {
      return current.recordLookupMiss();
    }
    EvidenceFinalizationReceiptRef receipt = lookupResult.receipt();
    if (!receipt.matches(request)) {
      throw new ProtocolRejectedException(Violation.RECEIPT_BINDING_MISMATCH);
    }
    return current.confirm(receipt);
  }

  public static void requireSameRequest(ActivityRequest expected, ActivityRequest actual) {
    Objects.requireNonNull(expected, "expected request must not be null");
    Objects.requireNonNull(actual, "actual request must not be null");
    if (!expected.sameOperation(actual)) {
      throw new ProtocolRejectedException(Violation.IDEMPOTENCY_CONFLICT);
    }
  }

  private static EvidenceActivityExecutionState requireState(EvidenceActivityExecutionState state) {
    return Objects.requireNonNull(state, "state must not be null");
  }

  public record ActivityRequest(
      String schemaVersion,
      EvidenceFinalizationReceiptRef.OperationType operationType,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      String manifestHash,
      long processRevision,
      long roomRevision,
      String operationKey,
      String requestHash,
      InvocationMode invocationMode) {

    public ActivityRequest {
      if (!"evidence-activity-request.v1".equals(schemaVersion)) {
        throw new IllegalArgumentException("schemaVersion must be evidence-activity-request.v1");
      }
      Objects.requireNonNull(operationType, "operationType must not be null");
      EvidenceOperationKeys.requireIdentifier(tenantSurrogate, "tenantSurrogate");
      EvidenceOperationKeys.requireIdentifier(caseId, "caseId");
      EvidenceOperationKeys.requireHash(manifestHash, "manifestHash");
      EvidenceOperationKeys.requireValid(operationKey);
      EvidenceOperationKeys.requireHash(requestHash, "requestHash");
      Objects.requireNonNull(invocationMode, "invocationMode must not be null");
      if (roomEpoch < 0 || fencingToken < 1 || processRevision < 0 || roomRevision < 0) {
        throw new IllegalArgumentException("request epoch, fence, and revisions must be valid");
      }
      if (!operationKey.startsWith(operationType.operationKeyPrefix())) {
        throw new IllegalArgumentException("request operationType must match operationKey");
      }
      EvidenceFinalizationReceiptRef.requireOperationBinding(
          operationType, operationKey, caseId, roomEpoch, manifestHash);
    }

    public boolean sameOperation(ActivityRequest other) {
      return operationType == other.operationType
          && tenantSurrogate.equals(other.tenantSurrogate)
          && caseId.equals(other.caseId)
          && roomEpoch == other.roomEpoch
          && fencingToken == other.fencingToken
          && manifestHash.equals(other.manifestHash)
          && processRevision == other.processRevision
          && roomRevision == other.roomRevision
          && operationKey.equals(other.operationKey)
          && requestHash.equals(other.requestHash);
    }
  }

  public record ReceiptLookupResult(
      String schemaVersion,
      ReceiptLookupStatus status,
      EvidenceFinalizationReceiptRef receipt) {

    public ReceiptLookupResult {
      if (!"evidence-receipt-lookup-result.v1".equals(schemaVersion)) {
        throw new IllegalArgumentException(
            "schemaVersion must be evidence-receipt-lookup-result.v1");
      }
      Objects.requireNonNull(status, "status must not be null");
      if (status == ReceiptLookupStatus.COMMITTED && receipt == null) {
        throw new IllegalArgumentException("committed lookup result requires a receipt");
      }
      if (status == ReceiptLookupStatus.NOT_COMMITTED && receipt != null) {
        throw new IllegalArgumentException("not committed lookup result cannot carry a receipt");
      }
    }

    public static ReceiptLookupResult notCommitted() {
      return new ReceiptLookupResult(
          "evidence-receipt-lookup-result.v1", ReceiptLookupStatus.NOT_COMMITTED, null);
    }

    public static ReceiptLookupResult committed(EvidenceFinalizationReceiptRef receipt) {
      return new ReceiptLookupResult(
          "evidence-receipt-lookup-result.v1", ReceiptLookupStatus.COMMITTED, receipt);
    }
  }

  public enum InvocationMode {
    INITIAL_LOOKUP,
    RETRY_RECONCILE_ONLY,
    CANCELLATION_RECONCILE_ONLY
  }

  public enum ReceiptLookupStatus {
    NOT_COMMITTED,
    COMMITTED
  }

  public enum Violation {
    IDEMPOTENCY_CONFLICT,
    RECEIPT_BINDING_MISMATCH,
    COMMITTED_RECEIPT_CONFLICT
  }

  public static final class ProtocolRejectedException extends IllegalStateException {

    private final Violation violation;

    private ProtocolRejectedException(Violation violation) {
      super("Evidence activity protocol rejected: " + violation.name());
      this.violation = violation;
    }

    public Violation violation() {
      return violation;
    }
  }
}
