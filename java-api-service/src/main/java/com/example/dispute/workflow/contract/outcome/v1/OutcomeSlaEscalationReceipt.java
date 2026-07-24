package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeSlaEscalationReceipt(
        String schemaVersion,
        String workflowId,
        String caseId,
        String receiptId,
        String receiptHash,
        String reviewTaskId,
        String frozenReviewPacketRef,
        String frozenReviewPacketHash,
        OutcomeWireTypes.SlaFactType factType,
        OutcomeWireTypes.ActorType actor,
        Instant deadlineAt,
        Instant committedAt,
        long epoch,
        long sourceRevision,
        long revision,
        long fence,
        long committedEventSequence,
        boolean executionAuthorized,
        boolean approvalRecordCreated,
        boolean syntheticOnly) {

    public static final String SCHEMA_VERSION = "outcome-sla-escalation-receipt.v1";

    public OutcomeSlaEscalationReceipt {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(receiptId, "receiptId");
        sha256(receiptHash, "receiptHash");
        identifier(reviewTaskId, "reviewTaskId");
        opaqueRef(frozenReviewPacketRef, "frozenReviewPacketRef");
        sha256(frozenReviewPacketHash, "frozenReviewPacketHash");
        required(factType, "factType");
        required(actor, "actor");
        instant(deadlineAt, "deadlineAt");
        instant(committedAt, "committedAt");
        coordinates(epoch, revision, fence);
        eventOrder(sourceRevision, revision, committedEventSequence);
        if (executionAuthorized || approvalRecordCreated) {
            throw new IllegalArgumentException("SLA escalation cannot authorize execution or create approval");
        }
    }
}
