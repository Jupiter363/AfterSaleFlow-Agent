package com.example.dispute.executor.domain.ledger;

import java.time.Instant;
import java.util.Objects;

/** Immutable reservation of one approved logical external effect. */
public record OutcomeOperation(
        String operationId,
        String projectionId,
        String tenantSurrogate,
        String caseId,
        long outcomeEpoch,
        long fencingToken,
        long processRevision,
        long outcomeRevision,
        OperationKind operationKind,
        long operationSequence,
        String operationKey,
        String requestHash,
        String reviewPacketId,
        int reviewPacketVersion,
        String reviewPacketHash,
        String reviewPacketActionHash,
        String approvalRecordId,
        String approvalHash,
        String decisionRequestHash,
        String decisionPolicyVersion,
        String actionRecordId,
        String actionSnapshotHash,
        String adapterId,
        String adapterVersion,
        RetryClass retryClass,
        String externalIdempotencyKey,
        boolean requiredForClosure,
        boolean compensable,
        Instant reservedAt) {

    public static final String SCHEMA_VERSION = "outcome-operation-command.v1";

    public OutcomeOperation {
        operationId = OutcomeLedgerValues.identifier(operationId, "operationId", 64);
        projectionId = OutcomeLedgerValues.identifier(projectionId, "projectionId", 64);
        tenantSurrogate = OutcomeLedgerValues.identifier(tenantSurrogate, "tenantSurrogate", 128);
        caseId = OutcomeLedgerValues.identifier(caseId, "caseId", 64);
        outcomeEpoch = OutcomeLedgerValues.nonNegative(outcomeEpoch, "outcomeEpoch");
        fencingToken = OutcomeLedgerValues.nonNegative(fencingToken, "fencingToken");
        processRevision = OutcomeLedgerValues.nonNegative(processRevision, "processRevision");
        outcomeRevision = OutcomeLedgerValues.nonNegative(outcomeRevision, "outcomeRevision");
        operationKind = Objects.requireNonNull(operationKind, "operationKind");
        if (operationSequence < 1) {
            throw new IllegalArgumentException("operationSequence must be positive");
        }
        operationKey = OutcomeLedgerValues.identifier(operationKey, "operationKey", 256);
        requestHash = OutcomeLedgerValues.sha256(requestHash, "requestHash");
        reviewPacketId = OutcomeLedgerValues.identifier(reviewPacketId, "reviewPacketId", 64);
        reviewPacketVersion = OutcomeLedgerValues.positive(reviewPacketVersion, "reviewPacketVersion");
        reviewPacketHash = OutcomeLedgerValues.sha256(reviewPacketHash, "reviewPacketHash");
        reviewPacketActionHash = OutcomeLedgerValues.boundedHash(
                reviewPacketActionHash, "reviewPacketActionHash");
        approvalRecordId = OutcomeLedgerValues.identifier(approvalRecordId, "approvalRecordId", 64);
        approvalHash = OutcomeLedgerValues.boundedHash(approvalHash, "approvalHash");
        decisionRequestHash = OutcomeLedgerValues.sha256(decisionRequestHash, "decisionRequestHash");
        decisionPolicyVersion = OutcomeLedgerValues.identifier(
                decisionPolicyVersion, "decisionPolicyVersion", 64);
        if (actionRecordId != null) {
            actionRecordId = OutcomeLedgerValues.identifier(actionRecordId, "actionRecordId", 64);
        }
        actionSnapshotHash = OutcomeLedgerValues.boundedHash(actionSnapshotHash, "actionSnapshotHash");
        adapterId = OutcomeLedgerValues.identifier(adapterId, "adapterId", 128);
        adapterVersion = OutcomeLedgerValues.identifier(adapterVersion, "adapterVersion", 64);
        retryClass = Objects.requireNonNull(retryClass, "retryClass");
        externalIdempotencyKey = OutcomeLedgerValues.identifier(
                externalIdempotencyKey, "externalIdempotencyKey", 256);
        reservedAt = OutcomeLedgerValues.instant(reservedAt, "reservedAt");
    }

    public void requireExactReplay(OutcomeOperation candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!equals(candidate)) {
            if (operationKey.equals(candidate.operationKey)
                    && !requestHash.equals(candidate.requestHash)) {
                throw rejected(
                        "OUTCOME_IDEMPOTENCY_CONFLICT",
                        "operation key was already bound to a different request hash");
            }
            throw rejected(
                    "OUTCOME_OPERATION_BINDING_CONFLICT",
                    "operation replay changed immutable packet, approval, action, or authority binding");
        }
    }

    private static OutcomeLedgerRejectedException rejected(String code, String message) {
        return new OutcomeLedgerRejectedException(code, message);
    }

    public enum OperationKind {
        OPERATION,
        COMPENSATION
    }

    public enum RetryClass {
        NON_RETRYABLE,
        BOUNDED_PRE_EFFECT,
        IDEMPOTENT_PROVIDER,
        STATUS_QUERY_REQUIRED
    }
}
