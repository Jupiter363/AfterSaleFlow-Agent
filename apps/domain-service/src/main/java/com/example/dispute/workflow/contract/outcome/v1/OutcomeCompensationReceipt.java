package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeCompensationReceipt(
        String schemaVersion,
        String workflowId,
        String caseId,
        String receiptId,
        String receiptHash,
        String originalOperationId,
        String originalSuccessReceiptId,
        String originalSuccessReceiptHash,
        String compensationOperationId,
        String compensationReceiptId,
        String compensationReceiptHash,
        String compensationRequestHash,
        String compensationPolicyVersion,
        long reverseOrder,
        OutcomeWireTypes.CompensationStatus status,
        Instant observedAt,
        long epoch,
        long sourceRevision,
        long revision,
        long fence,
        long committedEventSequence) {

    public static final String SCHEMA_VERSION = "outcome-compensation-receipt.v1";

    public OutcomeCompensationReceipt {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(receiptId, "receiptId");
        sha256(receiptHash, "receiptHash");
        identifier(originalOperationId, "originalOperationId");
        identifier(originalSuccessReceiptId, "originalSuccessReceiptId");
        sha256(originalSuccessReceiptHash, "originalSuccessReceiptHash");
        identifier(compensationOperationId, "compensationOperationId");
        identifier(compensationReceiptId, "compensationReceiptId");
        sha256(compensationReceiptHash, "compensationReceiptHash");
        if (!receiptId.equals(compensationReceiptId) || !receiptHash.equals(compensationReceiptHash)) {
            throw new IllegalArgumentException("compensation receipt identity must be self-consistent");
        }
        sha256(compensationRequestHash, "compensationRequestHash");
        versionPin(compensationPolicyVersion, "compensationPolicyVersion");
        count(reverseOrder, "reverseOrder");
        required(status, "status");
        instant(observedAt, "observedAt");
        coordinates(epoch, revision, fence);
        eventOrder(sourceRevision, revision, committedEventSequence);
    }
}
