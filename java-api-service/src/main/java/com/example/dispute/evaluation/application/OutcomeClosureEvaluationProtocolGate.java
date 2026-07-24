package com.example.dispute.evaluation.application;

import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeEvaluationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.util.Optional;

/** Read-only ordering gate over Java-committed closure and evaluation receipts. */
public final class OutcomeClosureEvaluationProtocolGate {

    private final OutcomeOperationLedger ledger;
    private final AuthoritativeClosureReceiptReader closureReceiptReader;

    public OutcomeClosureEvaluationProtocolGate(OutcomeOperationLedger ledger) {
        this(ledger, (ignoredExpectation, ignoredReceiptId) -> Optional.empty());
    }

    public OutcomeClosureEvaluationProtocolGate(
            OutcomeOperationLedger ledger,
            AuthoritativeClosureReceiptReader closureReceiptReader) {
        if (ledger == null || closureReceiptReader == null) {
            throw new IllegalArgumentException("ledger and closureReceiptReader are required");
        }
        this.ledger = ledger;
        this.closureReceiptReader = closureReceiptReader;
    }

    public ClosedSnapshot acceptCommittedClosure(
            OutcomeOperationLedger.ProjectionExpectation expectation,
            OutcomeClosureReceipt receipt) {
        if (expectation == null || receipt == null) {
            throw new IllegalArgumentException("expectation and closure receipt are required");
        }
        OutcomeClosureReceipt stored =
                closureReceiptReader
                        .findCommitted(expectation, receipt.receiptId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "authoritative closure receipt is missing"));
        if (!stored.equals(receipt)) {
            throw new IllegalStateException(
                    "claimed closure receipt does not exactly match the authoritative record");
        }
        OutcomeClosureReadiness readiness = ledger.closureReadiness(expectation);
        if (!readiness.closureReady()
                || readiness.unresolvedOperationCount() != 0
                || readiness.blockedOperationCount() != 0
                || readiness.reconciliationOperationCount() != 0
                || readiness.pendingCompensationCount() != 0
                || readiness.requiredOperationCount() != stored.requiredOperationCount()
                || !readiness.projectionId().equals(expectation.projectionId())
                || !readiness.tenantSurrogate().equals(expectation.tenantSurrogate())
                || !readiness.caseId().equals(expectation.caseId())
                || !stored.caseId().equals(expectation.caseId())
                || readiness.outcomeEpoch() != expectation.outcomeEpoch()
                || stored.epoch() != expectation.outcomeEpoch()
                || readiness.fencingToken() != expectation.fencingToken()
                || stored.fence() != expectation.fencingToken()
                || stored.sourceRevision() != expectation.outcomeRevision()
                || stored.revision() != expectation.outcomeRevision() + 1) {
            throw new IllegalStateException(
                    "Java ledger is not ready for the committed closure receipt");
        }
        return new ClosedSnapshot(
                stored.workflowId(),
                stored.caseId(),
                stored.closedSnapshotRef(),
                stored.closedSnapshotHash(),
                stored.closedAt(),
                stored.epoch(),
                stored.revision(),
                stored.fence(),
                stored.receiptId(),
                stored.receiptHash());
    }

    public EvaluationAcceptance acceptCommittedEvaluation(
            ClosedSnapshot closed, OutcomeEvaluationReceipt receipt) {
        if (closed == null || receipt == null
                || !closed.workflowId().equals(receipt.workflowId())
                || !closed.caseId().equals(receipt.caseId())
                || !closed.snapshotRef().equals(receipt.closedSnapshotRef())
                || !closed.snapshotHash().equals(receipt.closedSnapshotHash())
                || closed.epoch() != receipt.epoch()
                || closed.fence() != receipt.fence()
                || closed.revision() != receipt.sourceRevision()
                || receipt.revision() != closed.revision() + 1
                || receipt.status() != OutcomeWireTypes.EvaluationStatus.SUCCEEDED
                || receipt.caseReopened()) {
            throw new IllegalArgumentException(
                    "evaluation does not read the exact committed CLOSED snapshot");
        }
        return new EvaluationAcceptance(
                closed,
                receipt.receiptId(),
                receipt.receiptHash(),
                receipt.evaluationLedgerRef(),
                receipt.evaluationLedgerHash(),
                receipt.status().name(),
                false,
                true);
    }

    public static final class ClosedSnapshot {
        private final String workflowId;
        private final String caseId;
        private final String snapshotRef;
        private final String snapshotHash;
        private final java.time.Instant closedAt;
        private final long epoch;
        private final long revision;
        private final long fence;
        private final String closureReceiptId;
        private final String closureReceiptHash;

        private ClosedSnapshot(
                String workflowId,
                String caseId,
                String snapshotRef,
                String snapshotHash,
                java.time.Instant closedAt,
                long epoch,
                long revision,
                long fence,
                String closureReceiptId,
                String closureReceiptHash) {
            if (workflowId == null
                    || caseId == null
                    || snapshotRef == null
                    || snapshotHash == null
                    || !snapshotHash.matches("[0-9a-f]{64}")
                    || closedAt == null
                    || epoch < 1
                    || revision < 0
                    || fence < 1
                    || closureReceiptId == null
                    || closureReceiptHash == null
                    || !closureReceiptHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid committed CLOSED snapshot");
            }
            this.workflowId = workflowId;
            this.caseId = caseId;
            this.snapshotRef = snapshotRef;
            this.snapshotHash = snapshotHash;
            this.closedAt = closedAt;
            this.epoch = epoch;
            this.revision = revision;
            this.fence = fence;
            this.closureReceiptId = closureReceiptId;
            this.closureReceiptHash = closureReceiptHash;
        }

        public String workflowId() {
            return workflowId;
        }

        public String caseId() {
            return caseId;
        }

        public String snapshotRef() {
            return snapshotRef;
        }

        public String snapshotHash() {
            return snapshotHash;
        }

        public java.time.Instant closedAt() {
            return closedAt;
        }

        public long epoch() {
            return epoch;
        }

        public long revision() {
            return revision;
        }

        public long fence() {
            return fence;
        }

        public String closureReceiptId() {
            return closureReceiptId;
        }

        public String closureReceiptHash() {
            return closureReceiptHash;
        }
    }

    public record EvaluationAcceptance(
            ClosedSnapshot closedSnapshot,
            String evaluationReceiptId,
            String evaluationReceiptHash,
            String evaluationLedgerRef,
            String evaluationLedgerHash,
            String status,
            boolean caseReopened,
            boolean readOnly) {
        public EvaluationAcceptance {
            if (closedSnapshot == null
                    || evaluationReceiptId == null
                    || evaluationReceiptHash == null
                    || !evaluationReceiptHash.matches("[0-9a-f]{64}")
                    || evaluationLedgerRef == null
                    || evaluationLedgerHash == null
                    || !evaluationLedgerHash.matches("[0-9a-f]{64}")
                    || status == null
                    || caseReopened
                    || !readOnly) {
                throw new IllegalArgumentException("evaluation acceptance must remain read-only");
            }
        }
    }
}
