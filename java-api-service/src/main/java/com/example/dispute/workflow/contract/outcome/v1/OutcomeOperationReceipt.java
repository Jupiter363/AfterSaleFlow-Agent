package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeOperationReceipt(
        String schemaVersion,
        String workflowId,
        String caseId,
        String receiptId,
        String receiptHash,
        String operationId,
        String operationKeyHash,
        String requestHash,
        String externalIdempotencyKeyHash,
        String resultRef,
        String resultHash,
        OutcomeWireTypes.TerminalStatus terminalStatus,
        long operationSequence,
        boolean requiredForClosure,
        boolean compensable,
        Instant observedAt,
        long epoch,
        long sourceRevision,
        long revision,
        long fence,
        long committedEventSequence,
        OutcomeWireTypes.RuntimeMode runtimeMode,
        boolean syntheticNoop) {

    public static final String SCHEMA_VERSION = "outcome-operation-receipt.v1";

    public OutcomeOperationReceipt {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(receiptId, "receiptId");
        sha256(receiptHash, "receiptHash");
        identifier(operationId, "operationId");
        sha256(operationKeyHash, "operationKeyHash");
        sha256(requestHash, "requestHash");
        sha256(externalIdempotencyKeyHash, "externalIdempotencyKeyHash");
        opaqueRef(resultRef, "resultRef");
        sha256(resultHash, "resultHash");
        required(terminalStatus, "terminalStatus");
        OutcomeWireTypes.operationSequence(operationSequence);
        instant(observedAt, "observedAt");
        coordinates(epoch, revision, fence);
        eventOrder(sourceRevision, revision, committedEventSequence);
        if (runtimeMode != OutcomeWireTypes.RuntimeMode.TEMPORAL || syntheticNoop) {
            throw new IllegalArgumentException("authoritative operation receipt is formal-future only");
        }
    }
}
