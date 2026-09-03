package com.example.dispute.evaluation.application;

import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeClosureReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeEvaluationReceipt;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import java.util.Optional;

/** Read-only ordering gate over Java-committed closure and evaluation receipts. */
public final class OutcomeClosureEvaluationProtocolGate {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final AuthoritativeClosureReceiptReader closureReceiptReader;
    private final AuthoritativeEvaluationReceiptReader evaluationReceiptReader;

    /** Runtime is intentionally unregistered; the default boundary therefore fails closed. */
    public OutcomeClosureEvaluationProtocolGate() {
        this(
                (ignoredExpectation, ignoredReceiptId) -> Optional.empty(),
                ignoredLookup -> Optional.empty());
    }

    public OutcomeClosureEvaluationProtocolGate(
            AuthoritativeClosureReceiptReader closureReceiptReader,
            AuthoritativeEvaluationReceiptReader evaluationReceiptReader) {
        if (closureReceiptReader == null || evaluationReceiptReader == null) {
            throw new IllegalArgumentException(
                    "closureReceiptReader and evaluationReceiptReader are required");
        }
        this.closureReceiptReader = closureReceiptReader;
        this.evaluationReceiptReader = evaluationReceiptReader;
    }

    public ClosedSnapshot acceptCommittedClosure(
            OutcomeOperationLedger.ProjectionExpectation expectation,
            OutcomeClosureReceipt receipt) {
        if (expectation == null || receipt == null) {
            throw new IllegalArgumentException("expectation and closure receipt are required");
        }
        AuthoritativeClosureReceiptReader.CommittedClosureSnapshot committed =
                closureReceiptReader
                        .findCommittedWithReadiness(expectation, receipt.receiptId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "authoritative atomic closure snapshot is missing"));
        OutcomeClosureReceipt stored = committed.receipt();
        if (!stored.equals(receipt)) {
            throw new IllegalStateException(
                    "claimed closure receipt does not exactly match the authoritative record");
        }
        OutcomeClosureReadiness readiness = committed.readiness();
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
                || expectation.outcomeRevision() >= MAX_SAFE_INTEGER
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
        if (closed == null || receipt == null) {
            throw new IllegalArgumentException("closed snapshot and evaluation receipt are required");
        }
        OutcomeEvaluationReceipt stored =
                evaluationReceiptReader
                        .findCommitted(
                                new AuthoritativeEvaluationReceiptReader.EvaluationReceiptLookup(
                                        closed.workflowId(),
                                        closed.caseId(),
                                        closed.epoch(),
                                        closed.revision(),
                                        closed.fence(),
                                        receipt.receiptId()))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "authoritative evaluation receipt is missing"));
        if (!stored.equals(receipt)) {
            throw new IllegalStateException(
                    "claimed evaluation receipt does not exactly match the authoritative record");
        }
        if (!closed.workflowId().equals(stored.workflowId())
                || !closed.caseId().equals(stored.caseId())
                || !closed.snapshotRef().equals(stored.closedSnapshotRef())
                || !closed.snapshotHash().equals(stored.closedSnapshotHash())
                || closed.epoch() != stored.epoch()
                || closed.fence() != stored.fence()
                || closed.revision() != stored.sourceRevision()
                || closed.revision() >= MAX_SAFE_INTEGER
                || stored.revision() != closed.revision() + 1
                || stored.status() != OutcomeWireTypes.EvaluationStatus.SUCCEEDED
                || stored.caseReopened()) {
            throw new IllegalArgumentException(
                    "evaluation does not read the exact committed CLOSED snapshot");
        }
        return new EvaluationAcceptance(
                closed,
                stored.receiptId(),
                stored.receiptHash(),
                stored.evaluationLedgerRef(),
                stored.evaluationLedgerHash(),
                stored.status().name(),
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
                    || epoch < 0
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
