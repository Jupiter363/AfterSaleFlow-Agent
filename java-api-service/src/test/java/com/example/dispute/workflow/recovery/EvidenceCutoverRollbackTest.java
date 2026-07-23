package com.example.dispute.workflow.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.FailureBoundary;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.JavaReceiptObservation;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.JavaTruth;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.RecoveryAction;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.RollbackOutcome;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.RollbackRejectedException;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.RollbackRequest;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.ShadowState;
import com.example.dispute.workflow.shadow.evidence.EvidenceCutoverRollback.Violation;
import com.example.dispute.workflow.shadow.evidence.EvidenceEpochSelector.TrafficAuthorization;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.RuntimeMode;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EvidenceCutoverRollbackTest {

    private static final long EPOCH = 23;
    private static final long FENCE = 101;
    private static final long GRAPH_FENCE = 307;
    private static final String TIMER_KEY = "evidence.deadline.warn:CASE_P5_ROLLBACK:23:1";
    private static final String RECEIPT = "receipt://evidence/CASE_P5_ROLLBACK/merge/1";

    private final EvidenceCutoverRollback rollback = new EvidenceCutoverRollback();

    @ParameterizedTest
    @ValueSource(ints = {1, 8, 100})
    void firstEighthAndHundredthItemCrashFenceShadowAndPreserveJavaTruth(int crashOrdinal) {
        JavaTruth truth = javaTruth(null);

        RollbackOutcome outcome = rollback.rollback(request(
                FailureBoundary.ITEM_CRASH,
                truth,
                shadow(
                        RuntimeMode.SHADOW,
                        TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                        crashOrdinal,
                        crashOrdinal),
                JavaReceiptObservation.notQueried(),
                EPOCH,
                FENCE));

        assertThat(outcome.runtimeMode()).isEqualTo(RuntimeMode.DISABLED);
        assertThat(outcome.action()).isEqualTo(RecoveryAction.DISABLE_SYNTHETIC_AND_FENCE);
        assertThat(outcome.preservedJavaTruth()).isEqualTo(truth);
        assertThat(outcome.fencedGraphLeaseToken()).isEqualTo(GRAPH_FENCE + 1);
        assertThat(outcome.checkpointsRetained()).isTrue();
        assertThat(outcome.ledgersRetained()).isTrue();
        assertThat(outcome.legacyTimerStartCount()).isZero();
        assertThat(outcome.formalWriteCount()).isZero();
        assertThat(outcome.formalSinkReachable()).isFalse();
    }

    @Test
    void timerRacePreservesOriginalJavaDeadlineAndDoesNotDuplicateTheLegacyTimer() {
        JavaTruth truth = javaTruth(null);

        RollbackOutcome outcome = rollback.rollback(request(
                FailureBoundary.TIMER_RACE,
                truth,
                shadow(
                        RuntimeMode.SHADOW,
                        TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                        8,
                        0),
                JavaReceiptObservation.notQueried(),
                EPOCH,
                FENCE));

        assertThat(outcome.preservedJavaTruth()).isSameAs(truth);
        assertThat(outcome.preservedJavaTruth().originalDeadlineAt())
                .isEqualTo(Instant.parse("2026-07-23T12:00:00Z"));
        assertThat(outcome.preservedJavaTruth().legacyTimerOperationKey()).isEqualTo(TIMER_KEY);
        assertThat(outcome.preservedJavaTruth().activeLegacyTimerCount()).isOne();
        assertThat(outcome.legacyTimerStartCount()).isZero();
    }

    @Test
    void lostResponseWithoutJavaLedgerReceiptNeverInfersAFormalCommit() {
        JavaTruth truth = javaTruth(null);

        RollbackOutcome outcome = rollback.rollback(request(
                FailureBoundary.ACTIVITY_RESPONSE_LOST,
                truth,
                shadow(
                        RuntimeMode.SHADOW,
                        TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                        100,
                        0),
                JavaReceiptObservation.notCommitted(),
                EPOCH,
                FENCE));

        assertThat(outcome.action()).isEqualTo(RecoveryAction.DISABLE_SYNTHETIC_AND_FENCE);
        assertThat(outcome.reconciledReceiptRef()).isNull();
        assertThat(outcome.formalWriteCount()).isZero();
        assertThat(outcome.preservedJavaTruth()).isEqualTo(truth);
    }

    @Test
    void lostResponseReconcilesForwardOnlyFromTheMatchingJavaLedgerReceipt() {
        JavaTruth truth = javaTruth(RECEIPT);

        RollbackOutcome outcome = rollback.rollback(request(
                FailureBoundary.ACTIVITY_RESPONSE_LOST,
                truth,
                shadow(
                        RuntimeMode.SHADOW,
                        TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                        100,
                        0),
                JavaReceiptObservation.committed(RECEIPT),
                EPOCH,
                FENCE));

        assertThat(outcome.action())
                .isEqualTo(RecoveryAction.RECONCILE_FORWARD_FROM_JAVA_RECEIPT);
        assertThat(outcome.reconciledReceiptRef()).isEqualTo(RECEIPT);
        assertThat(outcome.preservedJavaTruth()).isSameAs(truth);
        assertThat(outcome.formalWriteCount()).isZero();
        assertThat(outcome.legacyTimerStartCount()).isZero();
    }

    @Test
    void staleUnsignedAndRealCaseRollbackAttemptsFailClosed() {
        JavaTruth truth = javaTruth(null);
        assertRejected(
                request(
                        FailureBoundary.ITEM_CRASH,
                        truth,
                        shadow(
                                RuntimeMode.SHADOW,
                                TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                                1,
                                1),
                        JavaReceiptObservation.notQueried(),
                        EPOCH,
                        FENCE - 1),
                Violation.STALE_JAVA_AUTHORITY);
        assertRejected(
                request(
                        FailureBoundary.ITEM_CRASH,
                        truth,
                        shadow(
                                RuntimeMode.SHADOW,
                                TrafficAuthorization.UNSIGNED_SYNTHETIC,
                                1,
                                1),
                        JavaReceiptObservation.notQueried(),
                        EPOCH,
                        FENCE),
                Violation.INELIGIBLE_TRAFFIC);
        assertRejected(
                request(
                        FailureBoundary.ITEM_CRASH,
                        truth,
                        shadow(
                                RuntimeMode.SHADOW,
                                TrafficAuthorization.JAVA_SIGNED_REAL_CASE,
                                1,
                                1),
                        JavaReceiptObservation.notQueried(),
                        EPOCH,
                        FENCE),
                Violation.INELIGIBLE_TRAFFIC);
    }

    @Test
    void mismatchedOrHistoryOnlyReceiptCannotAuthorizeForwardRecovery() {
        assertRejected(
                request(
                        FailureBoundary.ACTIVITY_RESPONSE_LOST,
                        javaTruth(RECEIPT),
                        shadow(
                                RuntimeMode.SHADOW,
                                TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                                8,
                                0),
                        JavaReceiptObservation.committed(
                                "receipt://evidence/CASE_P5_ROLLBACK/merge/other"),
                        EPOCH,
                        FENCE),
                Violation.RECEIPT_BINDING_MISMATCH);
        assertRejected(
                request(
                        FailureBoundary.ACTIVITY_RESPONSE_LOST,
                        javaTruth(null),
                        shadow(
                                RuntimeMode.SHADOW,
                                TrafficAuthorization.JAVA_SIGNED_SYNTHETIC,
                                8,
                                0),
                        JavaReceiptObservation.committed(RECEIPT),
                        EPOCH,
                        FENCE),
                Violation.RECEIPT_NOT_IN_JAVA_TRUTH);
    }

    private static JavaTruth javaTruth(String receipt) {
        Set<String> formalRefs = receipt == null
                ? Set.of("evidence://CASE_P5_ROLLBACK/submission/9")
                : Set.of("evidence://CASE_P5_ROLLBACK/submission/9", receipt);
        return new JavaTruth(
                "TENANT_P5_SYNTHETIC_ROLLBACK",
                "CASE_P5_ROLLBACK",
                EPOCH,
                FENCE,
                Instant.parse("2026-07-23T12:00:00Z"),
                TIMER_KEY,
                1,
                17,
                29,
                false,
                false,
                formalRefs,
                receipt);
    }

    private static ShadowState shadow(
            RuntimeMode runtimeMode,
            TrafficAuthorization authorization,
            int manifestItemCount,
            int crashItemOrdinal) {
        return new ShadowState(
                runtimeMode,
                authorization,
                manifestItemCount,
                crashItemOrdinal,
                GRAPH_FENCE);
    }

    private static RollbackRequest request(
            FailureBoundary boundary,
            JavaTruth truth,
            ShadowState shadow,
            JavaReceiptObservation receipt,
            long expectedEpoch,
            long expectedFence) {
        return new RollbackRequest(
                EvidenceCutoverRollback.ROLLBACK_REQUEST_VERSION,
                boundary,
                expectedEpoch,
                expectedFence,
                truth,
                shadow,
                receipt);
    }

    private void assertRejected(RollbackRequest request, Violation violation) {
        assertThatThrownBy(() -> rollback.rollback(request))
                .isInstanceOfSatisfying(
                        RollbackRejectedException.class,
                        rejected -> assertThat(rejected.violation()).isEqualTo(violation));
    }
}
