package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeEvaluationReceipt(
        String schemaVersion,
        String workflowId,
        String caseId,
        String receiptId,
        String receiptHash,
        String closedSnapshotRef,
        String closedSnapshotHash,
        String evaluationLedgerRef,
        String evaluationLedgerHash,
        OutcomeWireTypes.EvaluationStatus status,
        Instant evaluatedAt,
        long epoch,
        long sourceRevision,
        long revision,
        long fence,
        long committedEventSequence,
        boolean caseReopened) {

    public static final String SCHEMA_VERSION = "outcome-evaluation-receipt.v1";

    public OutcomeEvaluationReceipt {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(receiptId, "receiptId");
        sha256(receiptHash, "receiptHash");
        opaqueRef(closedSnapshotRef, "closedSnapshotRef");
        sha256(closedSnapshotHash, "closedSnapshotHash");
        opaqueRef(evaluationLedgerRef, "evaluationLedgerRef");
        sha256(evaluationLedgerHash, "evaluationLedgerHash");
        required(status, "status");
        instant(evaluatedAt, "evaluatedAt");
        coordinates(epoch, revision, fence);
        eventOrder(sourceRevision, revision, committedEventSequence);
        if (caseReopened) {
            throw new IllegalArgumentException("evaluation may not reopen a case");
        }
    }
}
