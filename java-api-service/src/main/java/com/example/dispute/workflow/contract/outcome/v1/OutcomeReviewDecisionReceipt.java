package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeReviewDecisionReceipt(
        String schemaVersion,
        String workflowId,
        String caseId,
        String receiptId,
        String receiptHash,
        String reviewTaskId,
        String reviewerAuthorityRef,
        String frozenReviewPacketRef,
        String frozenReviewPacketHash,
        String actionSnapshotRef,
        String actionSnapshotHash,
        String approvedActionSnapshotRef,
        String approvedActionSnapshotHash,
        String decisionRecordRef,
        String decisionRecordHash,
        String reasonRef,
        String reasonHash,
        String operationKeyHash,
        String requiredOperationSetRef,
        String requiredOperationSetHash,
        long requiredOperationCount,
        OutcomeWireTypes.ReviewDecision decision,
        boolean executionAuthorized,
        String requestHash,
        String idempotencyKeyHash,
        String policyVersion,
        long epoch,
        long sourceRevision,
        long revision,
        long fence,
        long committedEventSequence,
        Instant committedAt,
        boolean syntheticOnly) {

    public static final String SCHEMA_VERSION = "outcome-reviewer-decision-receipt.v1";

    public OutcomeReviewDecisionReceipt {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(receiptId, "receiptId");
        sha256(receiptHash, "receiptHash");
        identifier(reviewTaskId, "reviewTaskId");
        opaqueRef(reviewerAuthorityRef, "reviewerAuthorityRef");
        opaqueRef(frozenReviewPacketRef, "frozenReviewPacketRef");
        sha256(frozenReviewPacketHash, "frozenReviewPacketHash");
        opaqueRef(actionSnapshotRef, "actionSnapshotRef");
        sha256(actionSnapshotHash, "actionSnapshotHash");
        paired(approvedActionSnapshotRef, approvedActionSnapshotHash,
                "approvedActionSnapshotRef", "approvedActionSnapshotHash");
        opaqueRef(decisionRecordRef, "decisionRecordRef");
        sha256(decisionRecordHash, "decisionRecordHash");
        paired(reasonRef, reasonHash, "reasonRef", "reasonHash");
        required(decision, "decision");
        boolean approves = decision == OutcomeWireTypes.ReviewDecision.APPROVE
                || decision == OutcomeWireTypes.ReviewDecision.MODIFY_AND_APPROVE;
        optionalSha256(operationKeyHash, "operationKeyHash");
        opaqueRef(requiredOperationSetRef, "requiredOperationSetRef");
        sha256(requiredOperationSetHash, "requiredOperationSetHash");
        count(requiredOperationCount, "requiredOperationCount");
        if (executionAuthorized != approves) {
            throw new IllegalArgumentException("executionAuthorized must match the decision");
        }
        if (approves != (approvedActionSnapshotRef != null)) {
            throw new IllegalArgumentException("approved action snapshot must match approval semantics");
        }
        if (approves != (operationKeyHash != null)) {
            throw new IllegalArgumentException("operation key must match approval semantics");
        }
        sha256(requestHash, "requestHash");
        sha256(idempotencyKeyHash, "idempotencyKeyHash");
        versionPin(policyVersion, "policyVersion");
        coordinates(epoch, revision, fence);
        eventOrder(sourceRevision, revision, committedEventSequence);
        instant(committedAt, "committedAt");
    }
}
