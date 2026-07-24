package com.example.dispute.workflow.contract.outcome.v1;

import static com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.*;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OutcomeExecutionAttemptObservation(
        String schemaVersion,
        String workflowId,
        String caseId,
        String observationId,
        String observationHash,
        String operationId,
        String operationKeyHash,
        String requestHash,
        String externalIdempotencyKeyHash,
        long attemptNo,
        long operationSequence,
        boolean requiredForClosure,
        boolean compensable,
        OutcomeWireTypes.AttemptObservationStatus status,
        OutcomeWireTypes.ExternalEffectTruth externalEffectTruth,
        OutcomeWireTypes.OperationStatus operationStatus,
        Instant possibleDispatchAt,
        Instant observedAt,
        long epoch,
        long sourceRevision,
        long revision,
        long fence,
        long committedEventSequence,
        boolean closureBlocked,
        boolean blindRetryBlocked,
        boolean compensationBlocked) {

    public static final String SCHEMA_VERSION = "outcome-execution-attempt-observation.v1";

    public OutcomeExecutionAttemptObservation {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        identifier(workflowId, "workflowId");
        identifier(caseId, "caseId");
        identifier(observationId, "observationId");
        sha256(observationHash, "observationHash");
        identifier(operationId, "operationId");
        sha256(operationKeyHash, "operationKeyHash");
        sha256(requestHash, "requestHash");
        sha256(externalIdempotencyKeyHash, "externalIdempotencyKeyHash");
        positive(attemptNo, "attemptNo");
        count(operationSequence, "operationSequence");
        required(status, "status");
        required(externalEffectTruth, "externalEffectTruth");
        if (operationStatus != OutcomeWireTypes.OperationStatus.RECONCILING) {
            throw new IllegalArgumentException("ambiguous observation must keep operation reconciling");
        }
        instant(possibleDispatchAt, "possibleDispatchAt");
        instant(observedAt, "observedAt");
        coordinates(epoch, revision, fence);
        eventOrder(sourceRevision, revision, committedEventSequence);
        if (!closureBlocked || !blindRetryBlocked || !compensationBlocked) {
            throw new IllegalArgumentException("ambiguous observation must block closure, retry, and compensation");
        }
    }
}
