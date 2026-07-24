package com.example.dispute.evaluation.application;

import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import java.util.Optional;

/** Read-only Java boundary for closure receipts already committed by the authoritative writer. */
@FunctionalInterface
public interface AuthoritativeClosureReceiptReader {

    Optional<OutcomeClosureReceipt> findCommitted(
            OutcomeOperationLedger.ProjectionExpectation expectation,
            String closureReceiptId);
}
