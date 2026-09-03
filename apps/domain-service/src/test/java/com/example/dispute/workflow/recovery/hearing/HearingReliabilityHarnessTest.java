package com.example.dispute.workflow.recovery.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.recovery.hearing.HearingReliabilityHarness.FailureClassification;
import com.example.dispute.workflow.recovery.hearing.HearingReliabilityHarness.FaultBoundary;
import com.example.dispute.workflow.recovery.hearing.HearingReliabilityHarness.Invariant;
import com.example.dispute.workflow.recovery.hearing.HearingReliabilityHarness.Observation;
import com.example.dispute.workflow.recovery.hearing.HearingReliabilityHarness.Outcome;
import com.example.dispute.workflow.recovery.hearing.HearingReliabilityHarness.ReplayStatus;
import com.example.dispute.workflow.recovery.hearing.HearingReliabilityHarness.Scenario;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HearingReliabilityHarnessTest {

    private static final String CANDIDATE = "245cb500a45ba5597966b942922bc858a24bc608";
    private static final String FIXTURE = "a".repeat(64);
    private static final String STATE = "b".repeat(64);
    private static final String TRACE = "c".repeat(64);

    private final HearingReliabilityHarness harness = new HearingReliabilityHarness();

    @Test
    void killLostResponseStaleFenceAndRollbackAreDeterministicallyReplayable() {
        for (FaultBoundary boundary : FaultBoundary.values()) {
            Scenario scenario = scenario(boundary, FailureClassification.PRODUCT);
            var report = harness.requirePassing(
                    scenario,
                    ignored -> new Observation(
                            boundary.expectedOutcome(), boundary.requiredInvariants(), STATE, TRACE));

            assertThat(report.status()).isEqualTo(ReplayStatus.PASS);
            assertThat(report.observationHashes())
                    .hasSize(3)
                    .containsOnly(report.observationHashes().getFirst());
            assertThat(report.missingInvariants()).isEmpty();
        }
    }

    @Test
    void nondeterminismOrMissingInvariantFailsEngineeringEvidence() {
        Scenario scenario = scenario(
                FaultBoundary.JAVA_COMMIT_RESPONSE_LOST, FailureClassification.INFRA);
        AtomicInteger replay = new AtomicInteger();
        var nondeterministic = harness.verify(
                scenario,
                ignored -> new Observation(
                        Outcome.RESIGNAL_RECEIPT,
                        scenario.boundary().requiredInvariants(),
                        String.format("%064x", replay.incrementAndGet()),
                        TRACE));
        assertThat(nondeterministic.status()).isEqualTo(ReplayStatus.FAIL_NON_REPRODUCIBLE);

        assertThatThrownBy(() -> harness.requirePassing(
                        scenario,
                        ignored -> new Observation(
                                Outcome.RESIGNAL_RECEIPT,
                                Set.of(Invariant.COMMITTED_RECEIPT_REUSED),
                                STATE,
                                TRACE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay failed");
    }

    @Test
    void externalGateCanNeverBecomePromotionEvidence() {
        var report = harness.verify(
                scenario(FaultBoundary.ENGINEERING_ROLLBACK, FailureClassification.EXTERNAL_GATE),
                ignored -> new Observation(
                        Outcome.DISABLED,
                        FaultBoundary.ENGINEERING_ROLLBACK.requiredInvariants(),
                        STATE,
                        TRACE));

        assertThat(report.status()).isEqualTo(ReplayStatus.EXTERNAL_GATE_PENDING);
        assertThat(report.blocksPromotion()).isTrue();
    }

    private static Scenario scenario(
            FaultBoundary boundary, FailureClassification failureClassification) {
        return new Scenario(
                HearingReliabilityHarness.SCENARIO_V1,
                boundary,
                failureClassification,
                CANDIDATE,
                FIXTURE,
                7331,
                3);
    }
}
