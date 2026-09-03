package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeClosureReceipt(
        String schemaVersion,
        String workflowId,
        String caseId,
        String receiptId,
        String receiptHash,
        String approvalReceiptRef,
        String approvalReceiptHash,
        String approvedActionSnapshotRef,
        String approvedActionSnapshotHash,
        String requiredOperationSetRef,
        String requiredOperationSetHash,
        long requiredOperationCount,
        String terminalReceiptSetRef,
        String terminalReceiptSetHash,
        String closedSnapshotRef,
        String closedSnapshotHash,
        long finalCaseRevision,
        long unresolvedAmbiguousCount,
        long failedRequiredReceiptCount,
        long inFlightOperationCount,
        long inFlightCompensationCount,
        long unresolvedManualRecoveryCount,
        Instant closedAt,
        long epoch,
        long sourceRevision,
        long revision,
        long fence,
        long committedEventSequence,
        boolean syntheticNoop) {

    public static final String SCHEMA_VERSION = "outcome-closure-receipt.v1";

    public OutcomeClosureReceipt {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(receiptId, "receiptId");
        sha256(receiptHash, "receiptHash");
        opaqueRef(approvalReceiptRef, "approvalReceiptRef");
        sha256(approvalReceiptHash, "approvalReceiptHash");
        opaqueRef(approvedActionSnapshotRef, "approvedActionSnapshotRef");
        sha256(approvedActionSnapshotHash, "approvedActionSnapshotHash");
        opaqueRef(requiredOperationSetRef, "requiredOperationSetRef");
        sha256(requiredOperationSetHash, "requiredOperationSetHash");
        count(requiredOperationCount, "requiredOperationCount");
        opaqueRef(terminalReceiptSetRef, "terminalReceiptSetRef");
        sha256(terminalReceiptSetHash, "terminalReceiptSetHash");
        opaqueRef(closedSnapshotRef, "closedSnapshotRef");
        sha256(closedSnapshotHash, "closedSnapshotHash");
        count(finalCaseRevision, "finalCaseRevision");
        if (unresolvedAmbiguousCount != 0
                || failedRequiredReceiptCount != 0
                || inFlightOperationCount != 0
                || inFlightCompensationCount != 0
                || unresolvedManualRecoveryCount != 0) {
            throw new IllegalArgumentException("closure blockers must all be zero");
        }
        instant(closedAt, "closedAt");
        coordinates(epoch, revision, fence);
        eventOrder(sourceRevision, revision, committedEventSequence);
        if (syntheticNoop) {
            throw new IllegalArgumentException("synthetic no-op cannot satisfy closure");
        }
    }
}
