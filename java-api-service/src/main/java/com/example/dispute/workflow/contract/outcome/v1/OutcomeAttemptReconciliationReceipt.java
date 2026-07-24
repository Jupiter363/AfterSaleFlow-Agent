package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeAttemptReconciliationReceipt(
        String schemaVersion,
        String workflowId,
        String caseId,
        String receiptId,
        String receiptHash,
        String observationId,
        String observationHash,
        String operationId,
        String operationKeyHash,
        String requestHash,
        String externalIdempotencyKeyHash,
        long operationSequence,
        boolean requiredForClosure,
        boolean compensable,
        OutcomeWireTypes.ReconciliationResolution resolution,
        String authoritativeReceiptRef,
        String authoritativeReceiptHash,
        boolean retryAllowed,
        boolean manualRecoveryRequired,
        Instant reconciledAt,
        long epoch,
        long sourceRevision,
        long revision,
        long fence,
        long committedEventSequence) {

    public static final String SCHEMA_VERSION = "outcome-attempt-reconciliation-receipt.v1";

    public OutcomeAttemptReconciliationReceipt {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(receiptId, "receiptId");
        sha256(receiptHash, "receiptHash");
        identifier(observationId, "observationId");
        sha256(observationHash, "observationHash");
        identifier(operationId, "operationId");
        sha256(operationKeyHash, "operationKeyHash");
        sha256(requestHash, "requestHash");
        sha256(externalIdempotencyKeyHash, "externalIdempotencyKeyHash");
        count(operationSequence, "operationSequence");
        required(resolution, "resolution");
        paired(authoritativeReceiptRef, authoritativeReceiptHash,
                "authoritativeReceiptRef", "authoritativeReceiptHash");
        boolean terminalConfirmation = resolution == OutcomeWireTypes.ReconciliationResolution.CONFIRMED_SUCCESS
                || resolution == OutcomeWireTypes.ReconciliationResolution.CONFIRMED_FAILURE;
        if (terminalConfirmation != (authoritativeReceiptRef != null)) {
            throw new IllegalArgumentException("authoritative receipt must match reconciliation result");
        }
        if (retryAllowed != (resolution == OutcomeWireTypes.ReconciliationResolution.NOT_FOUND_SAFE_TO_RETRY)) {
            throw new IllegalArgumentException("retryAllowed must match reconciliation result");
        }
        if (manualRecoveryRequired != (resolution == OutcomeWireTypes.ReconciliationResolution.UNRESOLVED)) {
            throw new IllegalArgumentException("manualRecoveryRequired must match reconciliation result");
        }
        instant(reconciledAt, "reconciledAt");
        coordinates(epoch, revision, fence);
        eventOrder(sourceRevision, revision, committedEventSequence);
    }
}
