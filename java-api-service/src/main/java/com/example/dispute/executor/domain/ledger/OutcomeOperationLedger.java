package com.example.dispute.executor.domain.ledger;

import java.util.Optional;
import java.util.List;

/**
 * Java-authoritative persistence protocol for operation reservation and observed external facts.
 * It schedules or invokes no tool and grants no runtime authority.
 */
public interface OutcomeOperationLedger {

    OutcomeProcessProjection createProjection(OutcomeProcessProjection projection);

    OutcomeProcessProjection advanceProjection(
            ProjectionExpectation expectation,
            OutcomeProcessProjection.ProcessState nextState,
            java.time.Instant advancedAt);

    OutcomeOperation reserve(OutcomeOperation operation, OutcomeCompensationParent compensationParent);

    OutcomeAttemptObservation appendAttempt(OutcomeAttemptObservation observation);

    OutcomeOperationReceipt recordReceipt(OutcomeOperationReceipt receipt);

    Optional<OutcomeOperation> findOperation(OperationLookup lookup);

    Optional<OutcomeOperationReceipt> findReceipt(String operationId);

    List<OutcomeOperationState> readOperationStates(ProjectionExpectation expectation);

    List<OutcomeCompensationParent> findCompensationParents(ProjectionExpectation expectation);

    OutcomeClosureReadiness closureReadiness(ProjectionExpectation expectation);

    record ProjectionExpectation(
            String projectionId,
            String tenantSurrogate,
            String caseId,
            long outcomeEpoch,
            long fencingToken,
            long processRevision,
            long outcomeRevision) {

        public ProjectionExpectation {
            projectionId = OutcomeLedgerValues.identifier(projectionId, "projectionId", 64);
            tenantSurrogate = OutcomeLedgerValues.identifier(tenantSurrogate, "tenantSurrogate", 128);
            caseId = OutcomeLedgerValues.identifier(caseId, "caseId", 64);
            outcomeEpoch = OutcomeLedgerValues.nonNegative(outcomeEpoch, "outcomeEpoch");
            fencingToken = OutcomeLedgerValues.nonNegative(fencingToken, "fencingToken");
            processRevision = OutcomeLedgerValues.nonNegative(processRevision, "processRevision");
            outcomeRevision = OutcomeLedgerValues.nonNegative(outcomeRevision, "outcomeRevision");
        }
    }

    record OperationLookup(
            String tenantSurrogate, String caseId, long outcomeEpoch, String operationKey) {

        public OperationLookup {
            tenantSurrogate = OutcomeLedgerValues.identifier(tenantSurrogate, "tenantSurrogate", 128);
            caseId = OutcomeLedgerValues.identifier(caseId, "caseId", 64);
            outcomeEpoch = OutcomeLedgerValues.nonNegative(outcomeEpoch, "outcomeEpoch");
            operationKey = OutcomeLedgerValues.identifier(operationKey, "operationKey", 256);
        }
    }
}
