package com.example.dispute.executor.domain.ledger;

import java.time.Instant;
import java.util.Objects;

/** The sole authoritative terminal receipt for an operation. */
public record OutcomeOperationReceipt(
        String receiptId,
        String receiptHash,
        String operationId,
        String tenantSurrogate,
        String caseId,
        long outcomeEpoch,
        long fencingToken,
        String requestHash,
        ReceiptStatus receiptStatus,
        ReceiptAuthority receiptAuthority,
        String externalReceiptId,
        String responseRef,
        String responseHash,
        ClosureDisposition closureDisposition,
        Instant completedAt) {

    public static final String SCHEMA_VERSION = "outcome-operation-receipt.v1";

    public OutcomeOperationReceipt {
        receiptId = OutcomeLedgerValues.identifier(receiptId, "receiptId", 64);
        receiptHash = OutcomeLedgerValues.sha256(receiptHash, "receiptHash");
        operationId = OutcomeLedgerValues.identifier(operationId, "operationId", 64);
        tenantSurrogate = OutcomeLedgerValues.identifier(tenantSurrogate, "tenantSurrogate", 128);
        caseId = OutcomeLedgerValues.identifier(caseId, "caseId", 64);
        outcomeEpoch = OutcomeLedgerValues.nonNegative(outcomeEpoch, "outcomeEpoch");
        fencingToken = OutcomeLedgerValues.nonNegative(fencingToken, "fencingToken");
        requestHash = OutcomeLedgerValues.sha256(requestHash, "requestHash");
        receiptStatus = Objects.requireNonNull(receiptStatus, "receiptStatus");
        receiptAuthority = Objects.requireNonNull(receiptAuthority, "receiptAuthority");
        externalReceiptId = OutcomeLedgerValues.identifier(externalReceiptId, "externalReceiptId", 256);
        responseRef = OutcomeLedgerValues.immutableRef(responseRef, "responseRef");
        responseHash = OutcomeLedgerValues.sha256(responseHash, "responseHash");
        closureDisposition = Objects.requireNonNull(closureDisposition, "closureDisposition");
        completedAt = OutcomeLedgerValues.instant(completedAt, "completedAt");
        if (receiptStatus == ReceiptStatus.SUCCEEDED
                && closureDisposition != ClosureDisposition.SATISFIED) {
            throw new IllegalArgumentException("SUCCEEDED receipt must satisfy closure");
        }
        if (receiptStatus == ReceiptStatus.FAILED
                && closureDisposition == ClosureDisposition.SATISFIED) {
            throw new IllegalArgumentException("FAILED receipt cannot satisfy closure as success");
        }
    }

    public void requireExactReplay(OutcomeOperationReceipt candidate) {
        if (!equals(Objects.requireNonNull(candidate, "candidate"))) {
            throw new OutcomeLedgerRejectedException(
                    "OUTCOME_RECEIPT_CONFLICT", "operation already has a different authoritative receipt");
        }
    }

    public enum ReceiptStatus {
        SUCCEEDED,
        FAILED
    }

    public enum ReceiptAuthority {
        DIRECT_RESPONSE,
        PROVIDER_CALLBACK,
        PROVIDER_STATUS_QUERY,
        JAVA_RECONCILIATION
    }

    public enum ClosureDisposition {
        SATISFIED,
        BLOCKED,
        MANUAL_RECOVERY
    }
}
