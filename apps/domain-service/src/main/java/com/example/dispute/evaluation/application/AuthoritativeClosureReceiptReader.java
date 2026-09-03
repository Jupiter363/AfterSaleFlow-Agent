package com.example.dispute.evaluation.application;

import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import java.util.Optional;

/**
 * Read-only boundary for a closure receipt and readiness observed in one authoritative transaction.
 * Implementations must not assemble the pair from independent reads. The transaction must serialize
 * with the ledger's authoritative outcome-compensation-order scope before observing either value
 * and hold that serialization until the pair has been returned and the read transaction commits.
 */
@FunctionalInterface
public interface AuthoritativeClosureReceiptReader {

    Optional<CommittedClosureSnapshot> findCommittedWithReadiness(
            OutcomeOperationLedger.ProjectionExpectation expectation,
            String closureReceiptId);

    record CommittedClosureSnapshot(
            OutcomeClosureReceipt receipt,
            OutcomeClosureReadiness readiness) {

        public CommittedClosureSnapshot {
            if (receipt == null || readiness == null) {
                throw new IllegalArgumentException(
                        "committed closure receipt and readiness are required");
            }
        }
    }
}
