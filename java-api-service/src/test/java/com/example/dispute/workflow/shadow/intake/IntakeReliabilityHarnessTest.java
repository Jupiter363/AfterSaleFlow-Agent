package com.example.dispute.workflow.shadow.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.FailureClassification;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.FaultBoundary;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.Invariant;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.Observation;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.Outcome;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.ReplayReport;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.ReplayStatus;
import com.example.dispute.workflow.shadow.intake.IntakeReliabilityHarness.Scenario;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntakeReliabilityHarnessTest {

    private static final String CANDIDATE = "f3be4633690c121891b5080fda1babdf4faa6cf9";
    private static final String FIXTURE_HASH = "a".repeat(64);
    private static final String STATE_HASH = "b".repeat(64);
    private static final String TRACE_HASH = "c".repeat(64);

    private final IntakeReliabilityHarness harness = new IntakeReliabilityHarness();

    @Test
    void everyCrashRaceAndRollbackBoundaryProducesReproducibleTextFreeEvidence() {
        for (FaultBoundary boundary : FaultBoundary.values()) {
            Scenario scenario = scenario(boundary, FailureClassification.PRODUCT);

            ReplayReport report = harness.requirePassing(
                    scenario,
                    ignored -> new Observation(
                            outcome(boundary),
                            boundary.requiredInvariants(),
                            STATE_HASH,
                            TRACE_HASH));

            assertThat(report.passed()).isTrue();
            assertThat(report.reproducible()).isTrue();
            assertThat(report.missingInvariants()).isEmpty();
            assertThat(report.observationHashes())
                    .hasSize(3)
                    .containsOnly(report.observationHashes().getFirst());
            assertThat(report.scenarioId()).isEqualTo(scenario.scenarioId());
        }
    }

    @Test
    void retainsTheExplicitFailureClassificationWithoutGuessingFromTextOrExceptions() {
        for (FailureClassification classification : FailureClassification.values()) {
            Scenario scenario = scenario(FaultBoundary.GRAPH_BEFORE_MODEL, classification);

            ReplayReport report = harness.verify(
                    scenario,
                    ignored -> observation(
                            FaultBoundary.GRAPH_BEFORE_MODEL, STATE_HASH, TRACE_HASH));

            assertThat(report.failureClassification()).isEqualTo(classification);
            if (classification == FailureClassification.EXTERNAL_GATE) {
                assertThat(report.status()).isEqualTo(ReplayStatus.EXTERNAL_GATE_PENDING);
                assertThat(report.passed()).isFalse();
                assertThat(report.blocksEngineeringCheckpoint()).isFalse();
                assertThat(report.blocksPromotion()).isTrue();
            } else {
                assertThat(report.status()).isEqualTo(ReplayStatus.PASS);
                assertThat(report.passed()).isTrue();
            }
        }
    }

    @Test
    void rejectsANondeterministicProbeAndMissingRecoveryInvariant() {
        Scenario scenario = scenario(
                FaultBoundary.ROLLBACK_PRE_TERMINAL, FailureClassification.INFRA);
        AtomicInteger replay = new AtomicInteger();

        ReplayReport nondeterministic = harness.verify(
                scenario,
                ignored -> observation(
                        scenario.boundary(),
                        String.format("%064x", replay.incrementAndGet()),
                        TRACE_HASH));

        assertThat(nondeterministic.reproducible()).isFalse();
        assertThat(nondeterministic.passed()).isFalse();
        assertThatThrownBy(() -> harness.requirePassing(
                        scenario,
                        ignored -> new Observation(
                                Outcome.RECOVERED,
                                Set.of(Invariant.ONE_ACTIVE_WRITER),
                                STATE_HASH,
                                TRACE_HASH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not reproducible or violates an invariant");
    }

    @Test
    void reproducibleInfrastructureOrInvariantFailuresCannotPass() {
        Scenario scenario = scenario(
                FaultBoundary.JAVA_COMMIT_BEFORE_ACTIVITY_COMPLETION,
                FailureClassification.INFRA);

        ReplayReport report = harness.verify(
                scenario,
                ignored -> new Observation(
                        Outcome.INFRASTRUCTURE_UNAVAILABLE,
                        scenario.boundary().requiredInvariants(),
                        STATE_HASH,
                        TRACE_HASH));

        assertThat(report.reproducible()).isTrue();
        assertThat(report.safeOutcomes()).isFalse();
        assertThat(report.passed()).isFalse();
    }

    @Test
    void aSafeButWrongBoundaryOutcomeCannotPass() {
        Scenario scenario = scenario(
                FaultBoundary.ROLLBACK_AFTER_EVIDENCE, FailureClassification.PRODUCT);

        ReplayReport report = harness.verify(
                scenario,
                ignored -> new Observation(
                        Outcome.RECOVERED,
                        scenario.boundary().requiredInvariants(),
                        STATE_HASH,
                        TRACE_HASH));

        assertThat(report.safeOutcomes()).isFalse();
        assertThat(report.status()).isEqualTo(ReplayStatus.FAIL_OUTCOME);
    }

    @Test
    void rejectsRawTextInEveryDigestBearingFieldAndBoundsReplayWork() {
        assertThatThrownBy(() -> new Observation(
                        Outcome.RECOVERED, Set.of(), "private party text", TRACE_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateHash must be lowercase SHA-256");
        assertThatThrownBy(() -> new Scenario(
                        IntakeReliabilityHarness.SCENARIO_V1,
                        FaultBoundary.GRAPH_BEFORE_MODEL,
                        FailureClassification.PRODUCT,
                        CANDIDATE,
                        "raw fixture",
                        7,
                        3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixtureHash must be lowercase SHA-256");
        assertThatThrownBy(() -> new Scenario(
                        IntakeReliabilityHarness.SCENARIO_V1,
                        FaultBoundary.GRAPH_BEFORE_MODEL,
                        FailureClassification.PRODUCT,
                        CANDIDATE,
                        FIXTURE_HASH,
                        7,
                        17))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 2 and 16");
    }

    private static Scenario scenario(
            FaultBoundary boundary, FailureClassification failureClassification) {
        return new Scenario(
                IntakeReliabilityHarness.SCENARIO_V1,
                boundary,
                failureClassification,
                CANDIDATE,
                FIXTURE_HASH,
                20260722,
                3);
    }

    private static Observation observation(
            FaultBoundary boundary, String stateHash, String traceHash) {
        return new Observation(
                outcome(boundary), boundary.requiredInvariants(), stateHash, traceHash);
    }

    private static Outcome outcome(FaultBoundary boundary) {
        return boundary.expectedOutcome();
    }
}
