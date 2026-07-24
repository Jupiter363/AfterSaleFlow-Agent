package com.example.dispute.executor.domain.ledger;

import java.time.Instant;

/** Immutable exact parent operation/receipt binding for one compensation child. */
public record OutcomeCompensationParent(
        String bindingId,
        String bindingHash,
        String childOperationId,
        String parentOperationId,
        String parentReceiptId,
        String parentReceiptHash,
        String compensationPolicyVersion,
        long reverseOrder,
        String tenantSurrogate,
        String caseId,
        long outcomeEpoch,
        long fencingToken,
        Instant createdAt) {

    public static final String SCHEMA_VERSION = "outcome-compensation-parent-binding.v1";

    public OutcomeCompensationParent {
        bindingId = OutcomeLedgerValues.identifier(bindingId, "bindingId", 64);
        bindingHash = OutcomeLedgerValues.sha256(bindingHash, "bindingHash");
        childOperationId = OutcomeLedgerValues.identifier(childOperationId, "childOperationId", 64);
        parentOperationId = OutcomeLedgerValues.identifier(parentOperationId, "parentOperationId", 64);
        parentReceiptId = OutcomeLedgerValues.identifier(parentReceiptId, "parentReceiptId", 64);
        parentReceiptHash = OutcomeLedgerValues.sha256(parentReceiptHash, "parentReceiptHash");
        compensationPolicyVersion = OutcomeLedgerValues.identifier(
                compensationPolicyVersion, "compensationPolicyVersion", 64);
        if (reverseOrder < 1) {
            throw new IllegalArgumentException("reverseOrder must be positive");
        }
        tenantSurrogate = OutcomeLedgerValues.identifier(tenantSurrogate, "tenantSurrogate", 128);
        caseId = OutcomeLedgerValues.identifier(caseId, "caseId", 64);
        outcomeEpoch = OutcomeLedgerValues.nonNegative(outcomeEpoch, "outcomeEpoch");
        fencingToken = OutcomeLedgerValues.nonNegative(fencingToken, "fencingToken");
        createdAt = OutcomeLedgerValues.instant(createdAt, "createdAt");
        if (childOperationId.equals(parentOperationId)) {
            throw new IllegalArgumentException("compensation child cannot be its own parent");
        }
    }

    public void requireExactReplay(OutcomeCompensationParent candidate) {
        if (!equals(candidate)) {
            throw new OutcomeLedgerRejectedException(
                    "OUTCOME_COMPENSATION_PARENT_CONFLICT",
                    "compensation child was rebound to a different operation or receipt");
        }
    }
}
