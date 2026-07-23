package com.example.dispute.workflow.shadow.evidence;

import com.example.dispute.workflow.shadow.evidence.EvidenceEpochSelector.TrafficAuthorization;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.RuntimeMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic rollback policy for signed synthetic Evidence shadow work.
 *
 * <p>The policy fences only Graph shadow work. It returns the Java truth snapshot byte-for-byte at
 * the value level, never starts another legacy timer, and never infers a commit from a lost response
 * or Workflow history. Forward reconciliation requires an explicit matching Java-ledger receipt.
 */
public final class EvidenceCutoverRollback {

    public static final String ROLLBACK_REQUEST_VERSION = "evidence-cutover-rollback-request.v1";

    public RollbackOutcome rollback(RollbackRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.javaTruth().roomEpoch() != request.expectedJavaRoomEpoch()
                || request.javaTruth().fencingToken() != request.expectedJavaFencingToken()) {
            throw rejected(Violation.STALE_JAVA_AUTHORITY);
        }
        if (request.shadowState().runtimeMode() != RuntimeMode.SHADOW) {
            throw rejected(Violation.SHADOW_NOT_ACTIVE);
        }
        if (request.shadowState().authorization()
                != TrafficAuthorization.JAVA_SIGNED_SYNTHETIC) {
            throw rejected(Violation.INELIGIBLE_TRAFFIC);
        }
        if (request.failureBoundary() == FailureBoundary.ITEM_CRASH
                && !supportedCrashOrdinal(request.shadowState().crashItemOrdinal())) {
            throw rejected(Violation.UNSUPPORTED_CRASH_BOUNDARY);
        }
        if (request.failureBoundary() == FailureBoundary.TIMER_RACE
                && request.javaTruth().activeLegacyTimerCount() != 1) {
            throw rejected(Violation.JAVA_TIMER_AUTHORITY_MISSING);
        }

        String reconciledReceiptRef = reconcileReceipt(request);
        RecoveryAction action = reconciledReceiptRef == null
                ? RecoveryAction.DISABLE_SYNTHETIC_AND_FENCE
                : RecoveryAction.RECONCILE_FORWARD_FROM_JAVA_RECEIPT;
        return new RollbackOutcome(
                RuntimeMode.DISABLED,
                action,
                request.javaTruth(),
                Math.addExact(request.shadowState().graphLeaseFenceToken(), 1),
                true,
                true,
                0,
                0,
                false,
                reconciledReceiptRef);
    }

    private static String reconcileReceipt(RollbackRequest request) {
        JavaReceiptObservation observation = request.javaReceiptObservation();
        if (request.failureBoundary() != FailureBoundary.ACTIVITY_RESPONSE_LOST) {
            if (observation.status() != ReceiptStatus.NOT_QUERIED) {
                throw rejected(Violation.UNEXPECTED_RECEIPT_OBSERVATION);
            }
            return null;
        }
        if (observation.status() != ReceiptStatus.COMMITTED) {
            return null;
        }
        if (request.javaTruth().committedReceiptRef() == null) {
            throw rejected(Violation.RECEIPT_NOT_IN_JAVA_TRUTH);
        }
        if (!request.javaTruth().committedReceiptRef().equals(observation.receiptRef())) {
            throw rejected(Violation.RECEIPT_BINDING_MISMATCH);
        }
        return observation.receiptRef();
    }

    private static boolean supportedCrashOrdinal(int ordinal) {
        return ordinal == 1 || ordinal == 8 || ordinal == 100;
    }

    private static RollbackRejectedException rejected(Violation violation) {
        return new RollbackRejectedException(violation);
    }

    public enum FailureBoundary {
        ITEM_CRASH,
        TIMER_RACE,
        ACTIVITY_RESPONSE_LOST
    }

    public enum RecoveryAction {
        DISABLE_SYNTHETIC_AND_FENCE,
        RECONCILE_FORWARD_FROM_JAVA_RECEIPT
    }

    public enum ReceiptStatus {
        NOT_QUERIED,
        NOT_COMMITTED,
        COMMITTED
    }

    public enum Violation {
        STALE_JAVA_AUTHORITY,
        SHADOW_NOT_ACTIVE,
        INELIGIBLE_TRAFFIC,
        UNSUPPORTED_CRASH_BOUNDARY,
        JAVA_TIMER_AUTHORITY_MISSING,
        UNEXPECTED_RECEIPT_OBSERVATION,
        RECEIPT_NOT_IN_JAVA_TRUTH,
        RECEIPT_BINDING_MISMATCH
    }

    public record JavaTruth(
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            Instant originalDeadlineAt,
            String legacyTimerOperationKey,
            int activeLegacyTimerCount,
            long processRevision,
            long roomRevision,
            boolean warningSent,
            boolean deadlineExpired,
            Set<String> committedFormalRefs,
            String committedReceiptRef) {

        public JavaTruth {
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            Objects.requireNonNull(originalDeadlineAt, "originalDeadlineAt must not be null");
            if (roomEpoch < 1 || fencingToken < 1 || processRevision < 0 || roomRevision < 0) {
                throw new IllegalArgumentException("Java authority values must be valid");
            }
            if (activeLegacyTimerCount < 0 || activeLegacyTimerCount > 1) {
                throw new IllegalArgumentException("Java can own at most one active legacy timer");
            }
            if (activeLegacyTimerCount == 1) {
                requireText(legacyTimerOperationKey, "legacyTimerOperationKey");
            } else if (legacyTimerOperationKey != null) {
                throw new IllegalArgumentException(
                        "inactive Java timer cannot carry an active operation key");
            }
            committedFormalRefs = Set.copyOf(Objects.requireNonNull(
                    committedFormalRefs, "committedFormalRefs must not be null"));
            committedFormalRefs.forEach(ref -> requireText(ref, "committedFormalRef"));
            if (committedReceiptRef != null) {
                requireText(committedReceiptRef, "committedReceiptRef");
                if (!committedFormalRefs.contains(committedReceiptRef)) {
                    throw new IllegalArgumentException(
                            "committed receipt must be part of Java formal truth");
                }
            }
        }
    }

    public record ShadowState(
            RuntimeMode runtimeMode,
            TrafficAuthorization authorization,
            int manifestItemCount,
            int crashItemOrdinal,
            long graphLeaseFenceToken) {

        public ShadowState {
            Objects.requireNonNull(runtimeMode, "runtimeMode must not be null");
            if (runtimeMode != RuntimeMode.DISABLED && runtimeMode != RuntimeMode.SHADOW) {
                throw new IllegalArgumentException(
                        "rollback accepts only DISABLED or signed synthetic SHADOW");
            }
            if (manifestItemCount != 1 && manifestItemCount != 8 && manifestItemCount != 100) {
                throw new IllegalArgumentException("closed synthetic manifests contain 1, 8, or 100 items");
            }
            if (crashItemOrdinal < 0 || crashItemOrdinal > manifestItemCount) {
                throw new IllegalArgumentException("crashItemOrdinal must be within the manifest");
            }
            if (graphLeaseFenceToken < 1) {
                throw new IllegalArgumentException("graphLeaseFenceToken must be positive");
            }
        }
    }

    public record JavaReceiptObservation(ReceiptStatus status, String receiptRef) {

        public JavaReceiptObservation {
            Objects.requireNonNull(status, "status must not be null");
            if (status == ReceiptStatus.COMMITTED) {
                requireText(receiptRef, "receiptRef");
            } else if (receiptRef != null) {
                throw new IllegalArgumentException("only a committed observation can carry a receipt");
            }
        }

        public static JavaReceiptObservation notQueried() {
            return new JavaReceiptObservation(ReceiptStatus.NOT_QUERIED, null);
        }

        public static JavaReceiptObservation notCommitted() {
            return new JavaReceiptObservation(ReceiptStatus.NOT_COMMITTED, null);
        }

        public static JavaReceiptObservation committed(String receiptRef) {
            return new JavaReceiptObservation(ReceiptStatus.COMMITTED, receiptRef);
        }
    }

    public record RollbackRequest(
            String schemaVersion,
            FailureBoundary failureBoundary,
            long expectedJavaRoomEpoch,
            long expectedJavaFencingToken,
            JavaTruth javaTruth,
            ShadowState shadowState,
            JavaReceiptObservation javaReceiptObservation) {

        public RollbackRequest {
            if (!ROLLBACK_REQUEST_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be " + ROLLBACK_REQUEST_VERSION);
            }
            Objects.requireNonNull(failureBoundary, "failureBoundary must not be null");
            Objects.requireNonNull(javaTruth, "javaTruth must not be null");
            Objects.requireNonNull(shadowState, "shadowState must not be null");
            Objects.requireNonNull(
                    javaReceiptObservation, "javaReceiptObservation must not be null");
            if (expectedJavaRoomEpoch < 1 || expectedJavaFencingToken < 1) {
                throw new IllegalArgumentException("expected Java authority must be positive");
            }
        }
    }

    public record RollbackOutcome(
            RuntimeMode runtimeMode,
            RecoveryAction action,
            JavaTruth preservedJavaTruth,
            long fencedGraphLeaseToken,
            boolean checkpointsRetained,
            boolean ledgersRetained,
            int legacyTimerStartCount,
            int formalWriteCount,
            boolean formalSinkReachable,
            String reconciledReceiptRef) {

        public RollbackOutcome {
            if (runtimeMode != RuntimeMode.DISABLED) {
                throw new IllegalArgumentException("rollback must disable synthetic execution");
            }
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(preservedJavaTruth, "preservedJavaTruth must not be null");
            if (fencedGraphLeaseToken < 2) {
                throw new IllegalArgumentException("rollback must advance the Graph lease fence");
            }
            if (!checkpointsRetained || !ledgersRetained) {
                throw new IllegalArgumentException("rollback retains checkpoints and ledgers");
            }
            if (legacyTimerStartCount != 0 || formalWriteCount != 0 || formalSinkReachable) {
                throw new IllegalArgumentException(
                        "rollback cannot start timers or reach a formal writer");
            }
            if (action == RecoveryAction.RECONCILE_FORWARD_FROM_JAVA_RECEIPT
                    && reconciledReceiptRef == null) {
                throw new IllegalArgumentException("forward reconciliation requires a Java receipt");
            }
            if (action == RecoveryAction.DISABLE_SYNTHETIC_AND_FENCE
                    && reconciledReceiptRef != null) {
                throw new IllegalArgumentException(
                        "disabled recovery cannot claim a committed receipt");
            }
        }
    }

    public static final class RollbackRejectedException extends IllegalStateException {

        private final Violation violation;

        private RollbackRejectedException(Violation violation) {
            super("Evidence rollback rejected: " + violation.name());
            this.violation = violation;
        }

        public Violation violation() {
            return violation;
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
