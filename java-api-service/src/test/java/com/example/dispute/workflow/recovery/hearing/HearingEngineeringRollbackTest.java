package com.example.dispute.workflow.recovery.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.recovery.hearing.HearingEngineeringRollback.RollbackRequest;
import com.example.dispute.workflow.recovery.hearing.HearingEngineeringRollback.Violation;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerControl.SchedulerMode;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.RuntimeMode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HearingEngineeringRollbackTest {

    private final HearingEngineeringRollback rollback = new HearingEngineeringRollback();

    @Test
    void rollbackZerosSyntheticAllocationAndRetainsAllImmutableEvidence() {
        RollbackRequest request = request(0, false, HearingWriterMode.LEGACY, SchedulerMode.EXECUTOR);

        var outcome = rollback.rollback(request);

        assertThat(outcome.runtimeMode()).isEqualTo(RuntimeMode.DISABLED);
        assertThat(outcome.syntheticCohortBasisPoints()).isZero();
        assertThat(outcome.formalWriter()).isEqualTo(HearingWriterMode.LEGACY);
        assertThat(outcome.schedulerMode()).isEqualTo(SchedulerMode.EXECUTOR);
        assertThat(outcome.fencedGraphLeaseToken()).isEqualTo(18);
        assertThat(outcome.retainedCheckpointHashes()).isEqualTo(request.checkpointHashes());
        assertThat(outcome.retainedComparisonHashes()).isEqualTo(request.comparisonHashes());
        assertThat(outcome.retainedJavaReceiptHashes()).isEqualTo(request.javaReceiptHashes());
        assertThat(outcome.formalSinkReachable()).isFalse();
        assertThat(outcome.temporalAllocationEnabled()).isFalse();
    }

    @Test
    void temporalAllocationFormalSinkOrMissingLegacyWriterBlocksRollbackClaim() {
        assertRejected(
                request(1, false, HearingWriterMode.LEGACY, SchedulerMode.EXECUTOR),
                Violation.TEMPORAL_ALLOCATION_PRESENT);
        assertRejected(
                request(0, true, HearingWriterMode.LEGACY, SchedulerMode.EXECUTOR),
                Violation.FORMAL_SINK_REACHABLE);
        assertRejected(
                request(0, false, HearingWriterMode.SHADOW, SchedulerMode.DETECTOR),
                Violation.LEGACY_WRITER_NOT_ACTIVE);
    }

    private void assertRejected(RollbackRequest request, Violation violation) {
        assertThatThrownBy(() -> rollback.rollback(request))
                .isInstanceOf(HearingEngineeringRollback.RollbackRejectedException.class)
                .extracting(exception ->
                        ((HearingEngineeringRollback.RollbackRejectedException) exception)
                                .violation())
                .isEqualTo(violation);
    }

    private static RollbackRequest request(
            int temporalAllocation,
            boolean sink,
            HearingWriterMode writerMode,
            SchedulerMode schedulerMode) {
        return new RollbackRequest(
                RuntimeMode.SIGNED_SYNTHETIC_SHADOW,
                250,
                temporalAllocation,
                writerMode,
                schedulerMode,
                17,
                Set.of("a".repeat(64)),
                Set.of("b".repeat(64)),
                Set.of("c".repeat(64)),
                sink);
    }
}
