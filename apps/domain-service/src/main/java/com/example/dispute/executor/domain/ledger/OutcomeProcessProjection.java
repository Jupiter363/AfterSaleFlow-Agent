package com.example.dispute.executor.domain.ledger;

import java.time.Instant;
import java.util.Objects;

/** Fenced Java-owned Outcome process cursor. DRAFT and OUTCOME remain query projections. */
public record OutcomeProcessProjection(
        String projectionId,
        String tenantSurrogate,
        String caseId,
        String epochId,
        long outcomeEpoch,
        WriterMode writerMode,
        RuntimeMode runtimeMode,
        long fencingToken,
        long processRevision,
        long outcomeRevision,
        String decisionAuthorityReceiptId,
        String decisionRequestHash,
        String approvedOperationSetHash,
        long expectedRequiredOperationCount,
        ProcessState processState,
        Instant projectedAt,
        Instant updatedAt) {

    public static final String SCHEMA_VERSION = "outcome-process-projection.v1";

    public OutcomeProcessProjection {
        projectionId = OutcomeLedgerValues.identifier(projectionId, "projectionId", 64);
        tenantSurrogate = OutcomeLedgerValues.identifier(tenantSurrogate, "tenantSurrogate", 128);
        caseId = OutcomeLedgerValues.identifier(caseId, "caseId", 64);
        epochId = OutcomeLedgerValues.identifier(epochId, "epochId", 64);
        outcomeEpoch = OutcomeLedgerValues.nonNegative(outcomeEpoch, "outcomeEpoch");
        writerMode = Objects.requireNonNull(writerMode, "writerMode");
        runtimeMode = Objects.requireNonNull(runtimeMode, "runtimeMode");
        fencingToken = OutcomeLedgerValues.nonNegative(fencingToken, "fencingToken");
        processRevision = OutcomeLedgerValues.nonNegative(processRevision, "processRevision");
        outcomeRevision = OutcomeLedgerValues.nonNegative(outcomeRevision, "outcomeRevision");
        decisionAuthorityReceiptId = OutcomeLedgerValues.identifier(
                decisionAuthorityReceiptId, "decisionAuthorityReceiptId", 64);
        decisionRequestHash = OutcomeLedgerValues.sha256(
                decisionRequestHash, "decisionRequestHash");
        approvedOperationSetHash = OutcomeLedgerValues.boundedHash(
                approvedOperationSetHash, "approvedOperationSetHash");
        expectedRequiredOperationCount = OutcomeLedgerValues.nonNegative(
                expectedRequiredOperationCount, "expectedRequiredOperationCount");
        processState = Objects.requireNonNull(processState, "processState");
        projectedAt = OutcomeLedgerValues.instant(projectedAt, "projectedAt");
        updatedAt = OutcomeLedgerValues.instant(updatedAt, "updatedAt");
        if (updatedAt.isBefore(projectedAt)) {
            throw new IllegalArgumentException("updatedAt cannot precede projectedAt");
        }
        if (writerMode == WriterMode.LEGACY && runtimeMode != RuntimeMode.DISABLED) {
            throw new IllegalArgumentException("LEGACY Outcome projection must remain DISABLED");
        }
        if (writerMode == WriterMode.SHADOW
                && runtimeMode != RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW) {
            throw new IllegalArgumentException("SHADOW Outcome projection must be signed synthetic no-op");
        }
        if (writerMode == WriterMode.TEMPORAL && runtimeMode != RuntimeMode.TEMPORAL) {
            throw new IllegalArgumentException("TEMPORAL Outcome projection must remain formal");
        }
    }

    public enum WriterMode {
        LEGACY,
        SHADOW,
        TEMPORAL
    }

    public enum RuntimeMode {
        DISABLED,
        JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW,
        TEMPORAL
    }

    public enum ProcessState {
        REVIEW_WAIT,
        DECISION_RECORDED,
        OPERATIONS_RESERVED,
        OPERATIONS_RUNNING,
        RECONCILING,
        COMPENSATING,
        READY_TO_CLOSE,
        MANUAL_RECOVERY,
        CLOSED,
        EVALUATION_PENDING,
        EVALUATED
    }
}
