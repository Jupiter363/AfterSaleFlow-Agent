package com.example.dispute.workflow.recovery.hearing;

import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerControl.SchedulerMode;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.RuntimeMode;
import java.util.Objects;
import java.util.Set;

/** Forward-only rollback for engineering synthetic Hearing shadow. */
public final class HearingEngineeringRollback {

    public RollbackOutcome rollback(RollbackRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.runtimeMode() != RuntimeMode.SIGNED_SYNTHETIC_SHADOW) {
            throw rejected(Violation.SYNTHETIC_SHADOW_NOT_ACTIVE);
        }
        if (request.temporalAllocationCount() != 0) {
            throw rejected(Violation.TEMPORAL_ALLOCATION_PRESENT);
        }
        if (request.formalSinkReachable()) {
            throw rejected(Violation.FORMAL_SINK_REACHABLE);
        }
        if (request.formalWriter() != HearingWriterMode.LEGACY
                || request.schedulerMode() != SchedulerMode.EXECUTOR) {
            throw rejected(Violation.LEGACY_WRITER_NOT_ACTIVE);
        }
        return new RollbackOutcome(
                RuntimeMode.DISABLED,
                0,
                HearingWriterMode.LEGACY,
                SchedulerMode.EXECUTOR,
                Math.addExact(request.graphLeaseFenceToken(), 1),
                request.checkpointHashes(),
                request.comparisonHashes(),
                request.javaReceiptHashes(),
                false,
                false);
    }

    public record RollbackRequest(
            RuntimeMode runtimeMode,
            int syntheticCohortBasisPoints,
            int temporalAllocationCount,
            HearingWriterMode formalWriter,
            SchedulerMode schedulerMode,
            long graphLeaseFenceToken,
            Set<String> checkpointHashes,
            Set<String> comparisonHashes,
            Set<String> javaReceiptHashes,
            boolean formalSinkReachable) {

        public RollbackRequest {
            Objects.requireNonNull(runtimeMode, "runtimeMode must not be null");
            Objects.requireNonNull(formalWriter, "formalWriter must not be null");
            Objects.requireNonNull(schedulerMode, "schedulerMode must not be null");
            if (syntheticCohortBasisPoints < 1 || syntheticCohortBasisPoints > 10_000) {
                throw new IllegalArgumentException("synthetic cohort must be between 1 and 10000");
            }
            if (temporalAllocationCount < 0 || graphLeaseFenceToken < 1) {
                throw new IllegalArgumentException("rollback counters are invalid");
            }
            checkpointHashes = hashes(checkpointHashes, "checkpointHash");
            comparisonHashes = hashes(comparisonHashes, "comparisonHash");
            javaReceiptHashes = hashes(javaReceiptHashes, "javaReceiptHash");
        }
    }

    public record RollbackOutcome(
            RuntimeMode runtimeMode,
            int syntheticCohortBasisPoints,
            HearingWriterMode formalWriter,
            SchedulerMode schedulerMode,
            long fencedGraphLeaseToken,
            Set<String> retainedCheckpointHashes,
            Set<String> retainedComparisonHashes,
            Set<String> retainedJavaReceiptHashes,
            boolean formalSinkReachable,
            boolean temporalAllocationEnabled) {

        public RollbackOutcome {
            if (runtimeMode != RuntimeMode.DISABLED
                    || syntheticCohortBasisPoints != 0
                    || formalWriter != HearingWriterMode.LEGACY
                    || schedulerMode != SchedulerMode.EXECUTOR
                    || fencedGraphLeaseToken < 2
                    || formalSinkReachable
                    || temporalAllocationEnabled) {
                throw new IllegalArgumentException("rollback outcome violates Hearing safety");
            }
            retainedCheckpointHashes = hashes(retainedCheckpointHashes, "checkpointHash");
            retainedComparisonHashes = hashes(retainedComparisonHashes, "comparisonHash");
            retainedJavaReceiptHashes = hashes(retainedJavaReceiptHashes, "javaReceiptHash");
        }
    }

    public enum Violation {
        SYNTHETIC_SHADOW_NOT_ACTIVE,
        TEMPORAL_ALLOCATION_PRESENT,
        FORMAL_SINK_REACHABLE,
        LEGACY_WRITER_NOT_ACTIVE
    }

    public static final class RollbackRejectedException extends IllegalStateException {

        private final Violation violation;

        private RollbackRejectedException(Violation violation) {
            super("Hearing engineering rollback rejected: " + violation.name());
            this.violation = violation;
        }

        public Violation violation() {
            return violation;
        }
    }

    private static RollbackRejectedException rejected(Violation violation) {
        return new RollbackRejectedException(violation);
    }

    private static Set<String> hashes(Set<String> source, String field) {
        Set<String> copy = Set.copyOf(Objects.requireNonNull(source, field + "es must not be null"));
        copy.forEach(value -> {
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(field + " must be lowercase SHA-256");
            }
        });
        return copy;
    }
}
