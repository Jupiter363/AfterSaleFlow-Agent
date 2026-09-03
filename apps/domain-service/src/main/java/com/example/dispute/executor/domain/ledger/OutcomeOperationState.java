package com.example.dispute.executor.domain.ledger;

/** Read-only operation state derived from immutable reservation, attempts, and terminal receipt. */
public record OutcomeOperationState(
        String projectionId,
        String operationId,
        OutcomeOperation.OperationKind operationKind,
        long operationSequence,
        String operationKey,
        String requestHash,
        boolean requiredForClosure,
        boolean compensable,
        String tenantSurrogate,
        String caseId,
        long outcomeEpoch,
        long fencingToken,
        long processRevision,
        long outcomeRevision,
        Status status,
        String receiptId,
        String receiptHash,
        boolean javaAuthoritative,
        String parentOperationId,
        String parentReceiptId,
        String parentReceiptHash,
        String compensationPolicyVersion,
        Long reverseOrder) {

    public enum Status {
        RESERVED,
        INVOCATION_DISPATCHED,
        PRE_EFFECT_RETRYABLE_FAILURE,
        AMBIGUOUS,
        RECONCILING,
        NO_EFFECT_CONFIRMED,
        SUCCEEDED,
        FAILED,
        MANUAL_RECOVERY
    }
}
