package com.example.dispute.executor.domain.ledger;

/** Derived closure prerequisite counts; this record does not itself close a case. */
public record OutcomeClosureReadiness(
        String projectionId,
        String tenantSurrogate,
        String caseId,
        long outcomeEpoch,
        long fencingToken,
        long expectedRequiredOperationCount,
        long requiredOperationCount,
        long unresolvedOperationCount,
        long blockedOperationCount,
        long reconciliationOperationCount,
        long pendingCompensationCount,
        boolean closureReady) {

    public OutcomeClosureReadiness {
        expectedRequiredOperationCount = OutcomeLedgerValues.nonNegative(
                expectedRequiredOperationCount, "expectedRequiredOperationCount");
        requiredOperationCount = OutcomeLedgerValues.nonNegative(
                requiredOperationCount, "requiredOperationCount");
        unresolvedOperationCount = OutcomeLedgerValues.nonNegative(
                unresolvedOperationCount, "unresolvedOperationCount");
        blockedOperationCount = OutcomeLedgerValues.nonNegative(
                blockedOperationCount, "blockedOperationCount");
        reconciliationOperationCount = OutcomeLedgerValues.nonNegative(
                reconciliationOperationCount, "reconciliationOperationCount");
        pendingCompensationCount = OutcomeLedgerValues.nonNegative(
                pendingCompensationCount, "pendingCompensationCount");
        boolean authoritativeReadiness =
                requiredOperationCount == expectedRequiredOperationCount
                        && unresolvedOperationCount == 0
                        && blockedOperationCount == 0
                        && reconciliationOperationCount == 0
                        && pendingCompensationCount == 0;
        if (closureReady != authoritativeReadiness) {
            throw new OutcomeLedgerRejectedException(
                    "OUTCOME_CLOSURE_READINESS_CONFLICT",
                    "database closure readiness disagrees with Java authoritative prerequisites");
        }
    }
}
