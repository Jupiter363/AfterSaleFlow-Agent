package com.example.dispute.workflow.recovery.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.recovery.hearing.HearingRecoveryReconciler.AttemptObservation;
import com.example.dispute.workflow.recovery.hearing.HearingRecoveryReconciler.AuthoritySnapshot;
import com.example.dispute.workflow.recovery.hearing.HearingRecoveryReconciler.CommittedReceipt;
import com.example.dispute.workflow.recovery.hearing.HearingRecoveryReconciler.ReconciliationRequest;
import com.example.dispute.workflow.recovery.hearing.HearingRecoveryReconciler.RecoveryAction;
import org.junit.jupiter.api.Test;

class HearingRecoveryReconcilerTest {

    private static final String OPERATION = "a".repeat(64);
    private static final String RECEIPT = "b".repeat(64);
    private static final String CHECKPOINT = "c".repeat(64);

    private final HearingRecoveryReconciler reconciler = new HearingRecoveryReconciler();

    @Test
    void lostActivityResponseResignalsTheExactCommittedJavaReceipt() {
        CommittedReceipt receipt = new CommittedReceipt(RECEIPT, OPERATION, 7, 8, 12);
        var plan = reconciler.reconcile(new ReconciliationRequest(
                authority(8, 12),
                new AttemptObservation(4, 7, 12, OPERATION, CHECKPOINT, receipt, null)));

        assertThat(plan.action()).isEqualTo(RecoveryAction.RESIGNAL_COMMITTED_RECEIPT);
        assertThat(plan.receiptHash()).isEqualTo(RECEIPT);
        assertThat(plan.formalWriteAllowed()).isFalse();
        assertThat(plan.stageAdvanceAllowed()).isFalse();

        var acknowledged = reconciler.reconcile(new ReconciliationRequest(
                authority(8, 12),
                new AttemptObservation(4, 7, 12, OPERATION, CHECKPOINT, receipt, RECEIPT)));
        assertThat(acknowledged.action()).isEqualTo(RecoveryAction.CONSISTENT);
    }

    @Test
    void killRecoveryResumesCheckpointOrRetriesTheSameOperation() {
        var resume = reconciler.reconcile(new ReconciliationRequest(
                authority(7, 12),
                new AttemptObservation(4, 7, 12, OPERATION, CHECKPOINT, null, null)));
        var retry = reconciler.reconcile(new ReconciliationRequest(
                authority(7, 12),
                new AttemptObservation(4, 7, 12, OPERATION, null, null, null)));

        assertThat(resume.action()).isEqualTo(RecoveryAction.RESUME_GRAPH_CHECKPOINT);
        assertThat(resume.checkpointHash()).isEqualTo(CHECKPOINT);
        assertThat(retry.action()).isEqualTo(RecoveryAction.RETRY_OPERATION);
    }

    @Test
    void staleFenceAndConflictingReceiptNeverReachAWriter() {
        var stale = reconciler.reconcile(new ReconciliationRequest(
                authority(7, 12),
                new AttemptObservation(4, 7, 11, OPERATION, null, null, null)));
        var conflict = reconciler.reconcile(new ReconciliationRequest(
                authority(8, 12),
                new AttemptObservation(
                        4,
                        7,
                        12,
                        OPERATION,
                        null,
                        new CommittedReceipt(RECEIPT, "d".repeat(64), 7, 8, 12),
                        null)));

        assertThat(stale.action()).isEqualTo(RecoveryAction.REJECT_STALE_AUTHORITY);
        assertThat(conflict.action()).isEqualTo(RecoveryAction.HALT_CONFLICT);
        assertThat(stale.formalWriteAllowed()).isFalse();
        assertThat(conflict.stageAdvanceAllowed()).isFalse();
    }

    private static AuthoritySnapshot authority(long revision, long fence) {
        return new AuthoritySnapshot(
                4, revision, fence, HearingWriterMode.TEMPORAL, 6, "e".repeat(64));
    }
}
