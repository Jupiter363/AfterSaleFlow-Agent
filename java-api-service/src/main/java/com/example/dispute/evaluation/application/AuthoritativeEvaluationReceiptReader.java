package com.example.dispute.evaluation.application;

import com.example.dispute.workflow.contract.outcome.v1.OutcomeEvaluationReceipt;
import java.util.Optional;

/** Read-only Java boundary for evaluation receipts committed by the authoritative writer. */
@FunctionalInterface
public interface AuthoritativeEvaluationReceiptReader {

    Optional<OutcomeEvaluationReceipt> findCommitted(EvaluationReceiptLookup lookup);

    record EvaluationReceiptLookup(
            String workflowId,
            String caseId,
            long epoch,
            long sourceRevision,
            long fence,
            String receiptId) {

        private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

        public EvaluationReceiptLookup {
            if (workflowId == null
                    || workflowId.isBlank()
                    || caseId == null
                    || caseId.isBlank()
                    || epoch < 1
                    || sourceRevision < 0
                    || sourceRevision >= MAX_SAFE_INTEGER
                    || fence < 1
                    || receiptId == null
                    || receiptId.isBlank()) {
                throw new IllegalArgumentException("invalid authoritative evaluation lookup");
            }
        }
    }
}
